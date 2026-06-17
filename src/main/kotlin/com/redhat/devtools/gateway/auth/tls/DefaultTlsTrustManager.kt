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

import com.redhat.devtools.gateway.auth.code.OpenShiftAuthCodeFlow
import com.redhat.devtools.gateway.kubeconfig.KubeConfigNamedCluster
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import com.redhat.devtools.gateway.util.toServerBaseUrl
import io.kubernetes.client.util.KubeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            KubeConfigUtils.getClusterByServer(
                serverUrl,
                kubeConfigProvider()
            )

        if (namedCluster?.cluster?.insecureSkipTlsVerify == true) {
            return SslContextFactory.insecure()
        }

        val trustedCerts = getTrustedCerts(namedCluster, serverUri.host) + sessionTrustStore.get(serverUrl)

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

            val keyStore = persistentKeyStore.loadOrCreate()
            val persistentAlias = hostAlias(serverUri.host)

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

    /**
     * Resolves TLS trust for the API server and OAuth endpoints discovered from it.
     */
    suspend fun ensureOpenShiftTlsContext(
        apiServerUrl: String,
        decisionHandler: suspend (TlsServerCertificateInfo) -> TlsTrustDecision,
    ): TlsContext {
        val apiBaseUrl = URI(apiServerUrl).toServerBaseUrl()

        ensureTrusted(apiBaseUrl, decisionHandler)

        val apiTls = mergedContextFor(listOf(apiBaseUrl))
        val oauthUrls = runCatching {
            OpenShiftAuthCodeFlow.discoverOAuthEndpointBaseUrls(
                apiBaseUrl,
                apiTls.sslContext,
            )
        }.getOrDefault(emptyList())

        val allUrls = (listOf(apiBaseUrl) + oauthUrls).distinct()
        for (url in allUrls) {
            if (url != apiBaseUrl) {
                ensureTrusted(url, decisionHandler)
            }
        }

        return mergedContextFor(allUrls)
    }

    suspend fun mergedContextFor(serverUrls: Collection<String>): TlsContext {
        val configs = kubeConfigProvider()
        val keyStore = persistentKeyStore.loadOrCreate()
        val allCerts = mutableListOf<X509Certificate>()
        val sessionCerts = sessionTrustStore.allCertificates()

        for (serverUrl in serverUrls.distinct()) {
            val uri = URI(serverUrl)
            if (sessionCerts.isEmpty()) {
                KubeConfigUtils.getClusterByServer(serverUrl, configs)?.let {
                    allCerts += KubeConfigTlsUtils.extractCaCertificates(it)
                }
                val persistentCert = keyStore.getCertificate(hostAlias(uri.host))
                if (persistentCert is X509Certificate) {
                    allCerts += persistentCert
                }
            }
            allCerts += sessionTrustStore.get(serverUrl)
            allCerts += sessionCerts
        }

        require(allCerts.isNotEmpty()) { "No trusted certificates for: $serverUrls" }
        return SslContextFactory.fromTrustedCerts(allCerts.distinctBy { it.serialNumber })
    }

    /**
     * Returns the list of trusted X.509 certificates for a server URL.
     *
     * <p>Session trust (from TLS wizard) takes precedence over stale kubeconfig or persistent store entries.
     * If session certificates are present, they are added without duplicates. Otherwise, CA certificates
     * from the named cluster and any persisted certificate for the host are added.</p>
     *
     * @param namedCluster The optional Kubernetes cluster configuration from kubeconfig
     * @param host The hostname to look up in the persistent keystore (without scheme)
     * @return List of X.509 certificates to trust for TLS verification
     */
    private fun getTrustedCerts(namedCluster: KubeConfigNamedCluster?, host: String): List<X509Certificate> {
        val sessionCerts = sessionTrustStore.allCertificates()

        return buildList {
            if (sessionCerts.isEmpty()) {
                namedCluster?.let {
                    addAll(KubeConfigTlsUtils.extractCaCertificates(it))
                }
                val persistentCert = persistentKeyStore.loadOrCreate()
                    .getCertificate(hostAlias(host))
                if (persistentCert is X509Certificate) {
                    add(persistentCert)
                }
            } else {
                sessionCerts.forEach { cert ->
                    if (cert !in this) {
                        add(cert)
                    }
                }
            }
        }
    }

    private fun hostAlias(host: String) = "host:$host"

    private fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
        return digest.joinToString(":") { "%02x".format(it) }
    }
}
