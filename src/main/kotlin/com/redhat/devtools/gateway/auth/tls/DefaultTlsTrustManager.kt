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
import com.redhat.devtools.gateway.auth.code.OAuthDiscovery
import com.redhat.devtools.gateway.kubeconfig.KubeConfigNamedCluster
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import com.redhat.devtools.gateway.util.toServerBaseUrl
import io.kubernetes.client.util.KubeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException

class DefaultTlsTrustManager(
    private val kubeConfigProvider: suspend () -> List<KubeConfig>,
    private val kubeConfigWriter: suspend (KubeConfigNamedCluster, List<X509Certificate>) -> Unit,
    private val sessionTrustStore: SessionTlsTrustStore,
    private val persistentKeyStore: PersistentKeyStore,
    private val tlsProbe: (URI, TlsContext) -> Unit = { uri, ctx -> TlsProbe.connect(uri, ctx.sslContext) },
    private val oauthDiscovery: suspend (String, SSLContext) -> List<String> = { apiBaseUrl, sslContext ->
        OAuthDiscovery(apiBaseUrl, sslContext).endpointBaseUrls()
    },
) : TlsTrustManager {

    private val logger = thisLogger()

    override suspend fun createTlsContext(
        serverUrl: String,
        decisionHandler: suspend (TlsServerCertificateInfo) -> TlsTrustDecision,
        certificateAuthority: CertificateSource?,
        endpointKind: TlsEndpointKind,
    ): TlsContext =
        establishTrustForEndpoint(
            serverUrl,
            decisionHandler,
            certificateAuthority,
            endpointKind,
        )

    private suspend fun establishTrustForEndpoint(
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

        val namedCluster = KubeConfigUtils.getClusterByServer(serverUrl, kubeConfigProvider())
        if (checkForInsecureSkipTlsVerify(namedCluster, serverUrl)) {
            return SslContextFactory.insecure()
        }

        val trustedCerts = resolveCertificatesForUrls(listOf(serverUrl), certificateAuthority)

        tryTrustedCertProbe(serverUrl, serverUri, trustedCerts)?.let { return it }

        val certInfo = captureServerCertificate(serverUri, trustedCerts)
            ?: run {
                logger.warn("TLS trust: probe unexpectedly succeeded without trust for $serverUrl")
                return SslContextFactory.captureOnly() // should not normally succeed
            }

        val info = TlsServerCertificateInfo(
            serverUrl = serverUrl,
            certificateChain = certInfo.chain,
            fingerprintSha256 = sha256Fingerprint(certInfo.trustAnchor),
            problem = certInfo.problem,
            endpointKind = endpointKind,
        )

        logger.info("TLS trust: prompting user for ${endpointKind.label} at $serverUrl")

        return persistAndVerifyAcceptedTrust(
            serverUrl = serverUrl,
            trustedCerts = trustedCerts,
            namedCluster = namedCluster,
            serverUri = serverUri,
            certInfo = info,
            decisionHandler = decisionHandler,
        )
    }

    private fun checkForInsecureSkipTlsVerify(
        namedCluster: KubeConfigNamedCluster?,
        serverUrl: String,
    ): Boolean {
        if (namedCluster?.isSkipTlsVerify() == true) {
            logger.warn("TLS trust: using insecure skip for $serverUrl (kubeconfig insecure-skip-tls-verify)")
            return true
        }
        return false
    }

    private suspend fun tryTrustedCertProbe(
        serverUrl: String,
        serverUri: URI,
        trustedCerts: List<X509Certificate>,
    ): TlsContext? {
        if (trustedCerts.isEmpty()) {
            logger.info("TLS trust: no known certificate for $serverUrl; will capture server certificate")
            return null
        }

        logger.debug(
            "TLS trust: trying ${trustedCerts.size} known certificate(s) for $serverUrl " +
                "(session=${sessionTrustStore.get(serverUrl).size}, " +
                "preconfigured=${trustedCerts.size - sessionTrustStore.get(serverUrl).size})"
        )

        return try {
            val tlsContext = SslContextFactory.fromTrustedCerts(trustedCerts)
            withContext(Dispatchers.IO) { tlsProbe(serverUri, tlsContext) }
            logger.info("TLS trust: existing trust accepted for $serverUrl")
            tlsContext
        } catch (e: SSLHandshakeException) {
            logger.warn(
                "TLS trust: handshake failed with known certificate(s) for $serverUrl; will prompt (${e.message})"
            )
            null
        }
    }

    private data class CapturedCertInfo(
        val problem: TlsTrustProblem,
        val chain: List<X509Certificate>,
        val trustAnchor: X509Certificate,
    )

    private suspend fun captureServerCertificate(
        serverUri: URI,
        trustedCerts: List<X509Certificate>,
    ): CapturedCertInfo? {
        val captureContext = SslContextFactory.captureOnly()
        return try {
            withContext(Dispatchers.IO) { tlsProbe(serverUri, captureContext) }
            null // probe succeeded without throwing — no cert info, caller logs unexpected success
        } catch (e: SSLHandshakeException) {
            val chain = (captureContext.trustManager as? CapturingTrustManager)
                ?.serverCertificateChain?.toList() ?: throw e

            val trustAnchor = chain.first()
            CapturedCertInfo(
                problem = if (trustedCerts.isEmpty()) TlsTrustProblem.UNTRUSTED_CERTIFICATE
                    else TlsTrustProblem.CERTIFICATE_CHANGED,
                chain = chain,
                trustAnchor = trustAnchor,
            )
        }
    }

    private suspend fun persistAndVerifyAcceptedTrust(
        serverUrl: String,
        trustedCerts: List<X509Certificate>,
        namedCluster: KubeConfigNamedCluster?,
        serverUri: URI,
        certInfo: TlsServerCertificateInfo,
        decisionHandler: suspend (TlsServerCertificateInfo) -> TlsTrustDecision,
    ): TlsContext {
        logger.info(
            "TLS trust: prompting user for ${certInfo.endpointKind.label} at $serverUrl " +
                "(problem=${certInfo.problem}, fingerprint=${certInfo.fingerprintSha256})"
        )

        val decision = decisionHandler(certInfo)
        if (!decision.trusted) {
            logger.info("TLS trust: user rejected certificate for $serverUrl")
            throw TlsTrustRejectedException()
        }

        logger.info(
            "TLS trust: user accepted certificate for $serverUrl " +
                "(scope=${decision.scope}, endpoint=${certInfo.endpointKind.label})"
        )

        val trustAnchor = certInfo.certificateChain.first()
        val scope = decision.scope ?: error("Trusted decision without scope")
        persistAcceptedTrust(
            serverUrl = serverUrl,
            host = serverUri.host,
            trustAnchor = trustAnchor,
            namedCluster = namedCluster,
            scope = scope,
        )

        val finalCerts = (trustedCerts + trustAnchor).distinctBy { it.serialNumber }
        val tlsContext = SslContextFactory.fromTrustedCerts(finalCerts)
        withContext(Dispatchers.IO) { tlsProbe(serverUri, tlsContext) }
        logger.info("TLS trust: verified connection to $serverUrl after user acceptance")
        return tlsContext
    }

    private suspend fun persistAcceptedTrust(
        serverUrl: String,
        host: String,
        trustAnchor: X509Certificate,
        namedCluster: KubeConfigNamedCluster?,
        scope: TlsTrustScope,
    ) {
        when (scope) {
            TlsTrustScope.SESSION_ONLY -> {
                sessionTrustStore.put(serverUrl, listOf(trustAnchor))
            }

            TlsTrustScope.PERMANENT -> {
                sessionTrustStore.put(serverUrl, listOf(trustAnchor))

                if (namedCluster != null) {
                    kubeConfigWriter(namedCluster, listOf(trustAnchor))
                }

                val keyStore = persistentKeyStore.loadOrCreate()
                KeyStoreUtils.addCertificate(
                    keyStore,
                    hostAlias(host),
                    trustAnchor,
                )
                persistentKeyStore.save(keyStore)
            }
        }
    }

    /**
     * Returns a TLS context for the given OpenShift API server URL.
     *
     * If the kubeconfig indicates insecureSkipTlsVerify for the cluster, an insecure SSL context is returned.
     * Otherwise, the method ensures the API server and its OAuth URLs are trusted based on the provided decision
     * handler and optional certificate authority, and returns a merged TLS context.
     *
     * @param apiServerUrl The URL of the OpenShift API server.
     * @param decisionHandler A suspending function that evaluates TLS certificate information and returns a trust decision.
     * @param certificateAuthority An optional certificate source used as a trusted certificate authority.
     * @return The TLS context configured for the API server and OAuth endpoints.
     */
    override suspend fun createOpenShiftTlsContext(
        apiServerUrl: String,
        decisionHandler: suspend (TlsServerCertificateInfo) -> TlsTrustDecision,
        certificateAuthority: CertificateSource?,
    ): TlsContext {
        val apiBaseUrl = URI(apiServerUrl).toServerBaseUrl()
        logger.info("TLS trust: establishing OpenShift TLS context for API $apiBaseUrl")


        val apiTlsContext = establishTrustForEndpoint(
            apiBaseUrl,
            decisionHandler,
            certificateAuthority,
            TlsEndpointKind.API_SERVER,
        )

        if (apiTlsContext.isInsecure) {
            return apiTlsContext
        }

        val oauthUrls = getOAuthUrls(apiBaseUrl, apiTlsContext)

        for (oauthUrl in oauthUrls) {
            establishTrustForEndpoint(
                oauthUrl,
                decisionHandler,
                certificateAuthority,
                TlsEndpointKind.OAUTH,
            )
        }

        val allUrls = (listOf(apiBaseUrl) + oauthUrls).distinct()

        val merged = mergeTrustedContext(allUrls, certificateAuthority)
        logger.info(
            "TLS trust: OpenShift TLS context ready for ${allUrls.size} endpoint(s): " +
                allUrls.joinToString()
        )
        return merged
    }

    private suspend fun getOAuthUrls(apiBaseUrl: String, apiTlsContext: TlsContext): List<String> {
        val oauthUrls = oauthDiscovery(apiBaseUrl, apiTlsContext.sslContext)

        if (oauthUrls.isEmpty()) {
            logger.warn(
                "TLS trust: OAuth discovery returned no endpoints for $apiBaseUrl. " +
                        "Only the API server certificate will be trusted."
            )
        } else {
            logger.info("TLS trust: discovered OAuth endpoint host(s): ${oauthUrls.joinToString()}")
        }
        return oauthUrls
    }

    internal suspend fun mergeTrustedContext(
        serverUrls: Collection<String>,
        certificateAuthority: CertificateSource?,
    ): TlsContext {
        val certs = resolveCertificatesForUrls(serverUrls, certificateAuthority)
        require(certs.isNotEmpty()) {
            "No trusted certificates for: ${serverUrls.distinct().joinToString()}"
        }
        return SslContextFactory.fromTrustedCerts(certs)
    }


    /**
     * Resolves trusted X.509 certificates for a collection of server URLs by merging:
     * - CA certificates from an optional [certificateAuthority]
     * - CA certificates from each named cluster in kubeconfig (one per URL)
     * - Persistent certificate for each host from the persistent keystore, when no session certs exist
     * - Session certificates (both per-URL and all-sessions) regardless of whether session certs exist
     *
     * <p>Session trust (from TLS wizard) takes precedence over stale kubeconfig or persistent store entries.
     * If session certificates are present, they override the fall-through to CA/config/persistent-store.</p>
     *
     * @param serverUrls The URLs to resolve certificates for. If empty, returns an empty list.
     * @param certificateAuthority Optional CA to add as trusted certificates (added once, not per-URL).
     * @return List of X.509 certificates to trust for all given server URLs, deduplicated by serial number.
     */
    private suspend fun resolveCertificatesForUrls(
        serverUrls: Collection<String>,
        certificateAuthority: CertificateSource?,
    ): List<X509Certificate> {
        if (serverUrls.isEmpty()) return emptyList()

        val configs = kubeConfigProvider()
        val keyStore = persistentKeyStore.loadOrCreate()
        val sessionCerts = sessionTrustStore.allCertificates()
        val noSessionTrust = sessionCerts.isEmpty()

        return buildList {
            if (noSessionTrust) {
                certificateAuthority?.let {
                    addAll(KubeConfigTlsUtils.extractCaCertificates(it))
                }
            } else {
                addAll(sessionCerts)
            }

            for (serverUrl in serverUrls.distinct()) {
                addAll(sessionTrustStore.get(serverUrl))

                if (noSessionTrust) {
                    KubeConfigUtils.getClusterByServer(serverUrl, configs)?.let {
                        addAll(KubeConfigTlsUtils.extractCaCertificates(it))
                    }

                    val persistentCert = keyStore.getCertificate(hostAlias(URI(serverUrl).host))
                    if (persistentCert is X509Certificate) {
                        add(persistentCert)
                    }
                }
            }
        }.distinctBy { it.serialNumber }
    }

    private fun hostAlias(host: String) = "host:$host"

    private fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
        return digest.joinToString(":") { "%02x".format(it) }
    }
}
