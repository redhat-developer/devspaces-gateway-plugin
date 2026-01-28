/*
 * Copyright (c) 2025-2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.auth.tls

import com.redhat.devtools.gateway.kubeconfig.KubeConfigNamedCluster
import io.kubernetes.client.util.KubeConfig
import java.net.URI
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException

class DefaultTlsTrustManager(
    private val kubeConfigProvider: () -> List<KubeConfig>,
    private val kubeConfigWriter: (KubeConfigNamedCluster, List<X509Certificate>) -> Unit,
    private val sessionTrustStore: SessionTlsTrustStore,
    private val persistentKeyStore: PersistentKeyStore
) : TlsTrustManager {

    override suspend fun ensureTrusted(
        serverUrl: String,
        decisionHandler: suspend (TlsServerCertificateInfo) -> TlsTrustDecision
    ): TlsContext {

        val serverUri = URI(serverUrl)

        // 1️⃣ Locate kubeconfig cluster
        val namedCluster =
            KubeConfigTlsUtils.findClusterByServer(
                serverUrl,
                kubeConfigProvider()
            )

        // 2️⃣ insecure-skip-tls-verify
        if (namedCluster?.cluster?.insecureSkipTlsVerify == true) {
            return SslContextFactory.insecure()
        }

        // 3️⃣ Load all trusted certs (kubeconfig + session + persistent)
        val trustedCerts = mutableListOf<X509Certificate>()

        namedCluster?.let {
            trustedCerts += KubeConfigTlsUtils.extractCaCertificates(it)
        }

        trustedCerts += sessionTrustStore.get(serverUrl)

        // load persistent keystore cert for this host only
        val keyStore = persistentKeyStore.loadOrCreate()
        val persistentAlias = "host:${serverUri.host}"

        val persistentCert = keyStore.getCertificate(persistentAlias)
        if (persistentCert is X509Certificate) {
            trustedCerts += persistentCert
        }

        // 4️⃣ If we have trusted certs — try normal handshake first
        if (trustedCerts.isNotEmpty()) {
            try {
                val tlsContext = SslContextFactory.fromTrustedCerts(trustedCerts)
                TlsProbe.connect(serverUri, tlsContext.sslContext)
                return tlsContext
            } catch (e: SSLHandshakeException) {
                // Certificate changed or invalid → continue to capture
            }
        }

        // 5️⃣ Capture server certificate chain
        val captureContext = SslContextFactory.captureOnly()

        try {
            TlsProbe.connect(serverUri, captureContext.sslContext)
            return captureContext // should not normally succeed
        } catch (e: SSLHandshakeException) {

            val chain = (captureContext.trustManager as? CapturingTrustManager)
                ?.serverCertificateChain
                ?.toList()
                ?: throw e

            val trustAnchor = chain.first()

            val problem =
                if (trustedCerts.isEmpty())
                    TlsTrustProblem.UNTRUSTED_CERTIFICATE
                else
                    TlsTrustProblem.CERTIFICATE_CHANGED

            val info = TlsServerCertificateInfo(
                serverUrl = serverUrl,
                certificateChain = chain,
                fingerprintSha256 = sha256Fingerprint(trustAnchor),
                problem = problem
            )

            // 6️⃣ Ask UI layer
            val decision = decisionHandler(info)

            if (!decision.trusted) {
                throw TlsTrustRejectedException()
            }

            // 7️⃣ Persist based on scope
            when (decision.scope) {
                TlsTrustScope.SESSION_ONLY -> {
                    sessionTrustStore.put(serverUrl, listOf(trustAnchor))
                }

                TlsTrustScope.PERMANENT -> {

                    // session
                    sessionTrustStore.put(serverUrl, listOf(trustAnchor))

                    // kubeconfig
                    if (namedCluster != null) {
                        kubeConfigWriter(namedCluster, listOf(trustAnchor))
                    }

                    // persistent keystore (host-scoped)
                    KeyStoreUtils.addCertificate(
                        keyStore,
                        persistentAlias,
                        trustAnchor
                    )
                    persistentKeyStore.save(keyStore)
                }

                null -> error("Trusted decision without scope")
            }

            // 8️⃣ Return final trusted SSLContext
            val finalCerts = (trustedCerts + trustAnchor)
                .distinctBy { it.serialNumber }

            return SslContextFactory.fromTrustedCerts(finalCerts)
        }
    }

    /** Private helper: SHA-256 fingerprint of a certificate */
    private fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
