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
import kotlinx.coroutines.*
import java.net.URI
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException

class DefaultTlsTrustManager(
    private val kubeConfigProvider: suspend () -> List<KubeConfig>,
    private val kubeConfigWriter: suspend (KubeConfigNamedCluster, List<X509Certificate>) -> Unit,
    private val sessionTrustStore: SessionTlsTrustStore,
    private val persistentKeyStore: PersistentKeyStore
) : TlsTrustManager {

    override suspend fun ensureTrusted(
        serverUrl: String,
        decisionHandler: suspend (TlsServerCertificateInfo) -> TlsTrustDecision
    ): TlsContext {

        val serverUri = URI(serverUrl)

        val namedCluster =
            KubeConfigTlsUtils.findClusterByServer(
                serverUrl,
                kubeConfigProvider()
            )

        if (namedCluster?.cluster?.insecureSkipTlsVerify == true) {
            return SslContextFactory.insecure()
        }

        val trustedCerts = mutableListOf<X509Certificate>()
        namedCluster?.let {
            trustedCerts += KubeConfigTlsUtils.extractCaCertificates(it)
        }

        trustedCerts += sessionTrustStore.get(serverUrl)

        val keyStore = persistentKeyStore.loadOrCreate()
        val persistentAlias = "host:${serverUri.host}"

        val persistentCert = keyStore.getCertificate(persistentAlias)
        if (persistentCert is X509Certificate) {
            trustedCerts += persistentCert
        }

        if (trustedCerts.isNotEmpty()) {
            try {
                val tlsContext = SslContextFactory.fromTrustedCerts(trustedCerts)
                withContext(Dispatchers.IO) {
                    TlsProbe.connect(serverUri, tlsContext.sslContext)
                }
                return tlsContext
            } catch (e: SSLHandshakeException) {
                // Certificate changed or invalid → continue to capture
            }
        }

        val captureContext = SslContextFactory.captureOnly()

        try {
            withContext(Dispatchers.IO) {
                TlsProbe.connect(serverUri, captureContext.sslContext)
            }
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

            val decision = decisionHandler(info)
            if (!decision.trusted) {
                throw TlsTrustRejectedException()
            }

            when (decision.scope) {
                TlsTrustScope.SESSION_ONLY -> {
                    sessionTrustStore.put(serverUrl, listOf(trustAnchor))
                }

                TlsTrustScope.PERMANENT -> {
                    sessionTrustStore.put(serverUrl, listOf(trustAnchor))

                    if (namedCluster != null) {
                        kubeConfigWriter(namedCluster, listOf(trustAnchor))
                    }
                    KeyStoreUtils.addCertificate(
                        keyStore,
                        persistentAlias,
                        trustAnchor
                    )
                    persistentKeyStore.save(keyStore)
                }

                null -> error("Trusted decision without scope")
            }

            val finalCerts = (trustedCerts + trustAnchor)
                .distinctBy { it.serialNumber }

            return SslContextFactory.fromTrustedCerts(finalCerts)
        }
    }

    /** Private helper: SHA-256 fingerprint of a certificate */
    private fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
        return digest.joinToString(":") { "%02x".format(it) }
    }
}
