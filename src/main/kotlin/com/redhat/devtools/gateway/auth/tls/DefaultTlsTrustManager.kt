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

import com.intellij.openapi.diagnostic.thisLogger
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

    private val logger = thisLogger()

    override suspend fun ensureTrusted(
        serverUrl: String,
        decisionHandler: suspend (TlsServerCertificateInfo) -> TlsTrustDecision,
        certificateAuthority: CertificateSource?,
        endpointKind: TlsEndpointKind,
    ): TlsContext {

        val serverUri = URI(serverUrl)
        logger.info(
            "TLS trust: probing ${endpointKind.label} at $serverUrl " +
                "(wizard CA=${certificateAuthority != null}, kind=$endpointKind)"
        )

        val namedCluster =
            KubeConfigUtils.getClusterByServer(
                serverUrl,
                kubeConfigProvider()
            )

        if (namedCluster?.cluster?.insecureSkipTlsVerify == true) {
            logger.warn("TLS trust: using insecure skip for $serverUrl (kubeconfig insecure-skip-tls-verify)")
            return SslContextFactory.insecure()
        }

        val trustedCerts = getTrustedCerts(namedCluster, serverUri.host, certificateAuthority) +
            sessionTrustStore.get(serverUrl)

        if (trustedCerts.isNotEmpty()) {
            logger.debug(
                "TLS trust: trying ${trustedCerts.size} known certificate(s) for $serverUrl " +
                    "(session=${sessionTrustStore.get(serverUrl).size}, " +
                    "preconfigured=${trustedCerts.size - sessionTrustStore.get(serverUrl).size})"
            )
            try {
                val tlsContext = SslContextFactory.fromTrustedCerts(trustedCerts)
                withContext(Dispatchers.IO) {
                    TlsProbe.connect(serverUri, tlsContext.sslContext)
                }
                logger.info("TLS trust: existing trust accepted for $serverUrl")
                return tlsContext
            } catch (e: SSLHandshakeException) {
                logger.warn(
                    "TLS trust: handshake failed with known certificate(s) for $serverUrl; " +
                        "will prompt (${e.message})"
                )
            }
        } else {
            logger.info("TLS trust: no known certificate for $serverUrl; will capture server certificate")
        }

        val captureContext = SslContextFactory.captureOnly()

        try {
            withContext(Dispatchers.IO) {
                TlsProbe.connect(serverUri, captureContext.sslContext)
            }
            logger.warn("TLS trust: probe unexpectedly succeeded without trust for $serverUrl")
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
                problem = problem,
                endpointKind = endpointKind,
            )

            logger.info(
                "TLS trust: prompting user for ${endpointKind.label} at $serverUrl " +
                    "(problem=$problem, fingerprint=${info.fingerprintSha256})"
            )

            val decision = decisionHandler(info)
            if (!decision.trusted) {
                logger.info("TLS trust: user rejected certificate for $serverUrl")
                throw TlsTrustRejectedException()
            }

            logger.info(
                "TLS trust: user accepted certificate for $serverUrl " +
                    "(scope=${decision.scope}, endpoint=${endpointKind.label})"
            )

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

            val tlsContext = SslContextFactory.fromTrustedCerts(finalCerts)
            withContext(Dispatchers.IO) {
                TlsProbe.connect(serverUri, tlsContext.sslContext)
            }
            logger.info("TLS trust: verified connection to $serverUrl after user acceptance")
            return tlsContext
        }
    }

    /**
     * Resolves TLS trust for the API server and OAuth endpoints discovered from it.
     */
    suspend fun ensureOpenShiftTlsContext(
        apiServerUrl: String,
        decisionHandler: suspend (TlsServerCertificateInfo) -> TlsTrustDecision,
        certificateAuthority: CertificateSource? = null,
    ): TlsContext {
        val apiBaseUrl = URI(apiServerUrl).toServerBaseUrl()
        logger.info("TLS trust: establishing OpenShift TLS context for API $apiBaseUrl")

        ensureTrusted(
            apiBaseUrl,
            decisionHandler,
            certificateAuthority,
            TlsEndpointKind.API_SERVER,
        )

        val apiTls = mergedContextFor(listOf(apiBaseUrl), certificateAuthority)
        val oauthUrls = try {
            OpenShiftAuthCodeFlow.discoverOAuthEndpointBaseUrls(
                apiBaseUrl,
                apiTls.sslContext,
            )
        } catch (e: Exception) {
            logger.error(
                "TLS trust: failed to discover OAuth endpoints from $apiBaseUrl. " +
                    "Login may fail if the OAuth host uses a different certificate.",
                e
            )
            throw e
        }

        if (oauthUrls.isEmpty()) {
            logger.warn(
                "TLS trust: OAuth discovery returned no endpoints for $apiBaseUrl. " +
                    "Only the API server certificate will be trusted."
            )
        } else {
            logger.info("TLS trust: discovered OAuth endpoint host(s): ${oauthUrls.joinToString()}")
        }

        val allUrls = (listOf(apiBaseUrl) + oauthUrls).distinct()
        for (url in allUrls) {
            if (url != apiBaseUrl) {
                ensureTrusted(
                    url,
                    decisionHandler,
                    certificateAuthority,
                    TlsEndpointKind.OAUTH,
                )
            }
        }

        val merged = mergedContextFor(allUrls, certificateAuthority)
        logger.info(
            "TLS trust: OpenShift TLS context ready for ${allUrls.size} endpoint(s): " +
                allUrls.joinToString()
        )
        return merged
    }

    suspend fun mergedContextFor(
        serverUrls: Collection<String>,
        certificateAuthority: CertificateSource? = null,
    ): TlsContext {
        val configs = kubeConfigProvider()
        val keyStore = persistentKeyStore.loadOrCreate()
        val allCerts = mutableListOf<X509Certificate>()
        val sessionCerts = sessionTrustStore.allCertificates()

        for (serverUrl in serverUrls.distinct()) {
            val uri = URI(serverUrl)
            if (sessionCerts.isEmpty()) {
                certificateAuthority?.let {
                    allCerts += KubeConfigTlsUtils.extractCaCertificates(it)
                }
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
    private fun getTrustedCerts(
        namedCluster: KubeConfigNamedCluster?,
        host: String,
        certificateAuthority: CertificateSource?,
    ): List<X509Certificate> {
        val sessionCerts = sessionTrustStore.allCertificates()

        return buildList {
            if (sessionCerts.isEmpty()) {
                certificateAuthority?.let {
                    addAll(KubeConfigTlsUtils.extractCaCertificates(it))
                }
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
