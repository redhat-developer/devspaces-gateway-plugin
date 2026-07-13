/*
 * Copyright (c) 2026 Red Hat, Inc.
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

import com.redhat.devtools.gateway.kubeconfig.KubeConfigTestHelpers
import io.kubernetes.client.util.KubeConfig
import java.math.BigInteger
import java.net.URI
import java.nio.file.Files
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

object TlsTrustManagerTestFixtures {

    const val API_SERVER_URL = "https://api.example.com:6443"
    const val OAUTH_URL_1 = "https://oauth.example.com"
    const val OAUTH_URL_2 = "https://oauth2.example.com"

    fun tempPersistentKeyStore(): PersistentKeyStore =
        PersistentKeyStore(Files.createTempDirectory("tls-trust").resolve("truststore.p12"))

    fun kubeConfigWithInsecureSkip(serverUrl: String = API_SERVER_URL): KubeConfig =
        KubeConfigTestHelpers.createMockKubeConfig(
            clusters = listOf(
                mapOf(
                    "name" to "dogfood",
                    "cluster" to mapOf(
                        "server" to serverUrl,
                        "insecure-skip-tls-verify" to true,
                    ),
                ),
            ),
        )

    fun kubeConfigWithCa(
        serverUrl: String = API_SERVER_URL,
        caCert: X509Certificate = TlsTestCertificates.caCertificate(),
    ): KubeConfig =
        KubeConfigTestHelpers.createMockKubeConfig(
            clusters = listOf(
                mapOf(
                    "name" to "dogfood",
                    "cluster" to mapOf(
                        "server" to serverUrl,
                        "certificate-authority-data" to KubeConfigCertEncoder.encode(caCert),
                    ),
                ),
            ),
        )

    fun createManager(
        kubeConfigProvider: suspend () -> List<KubeConfig> = { emptyList() },
        kubeConfigWriter: suspend (com.redhat.devtools.gateway.kubeconfig.KubeConfigNamedCluster, List<X509Certificate>) -> Unit = { _, _ -> },
        sessionTrustStore: SessionTlsTrustStore = SessionTlsTrustStore(),
        persistentKeyStore: PersistentKeyStore = tempPersistentKeyStore(),
        tlsProbe: (URI, TlsContext) -> Unit = successTlsProbe(),
        oauthDiscovery: suspend (String, SSLContext) -> List<String> = { _, _ -> emptyList() },
    ): DefaultTlsTrustManager =
        DefaultTlsTrustManager(
            kubeConfigProvider = kubeConfigProvider,
            kubeConfigWriter = kubeConfigWriter,
            sessionTrustStore = sessionTrustStore,
            persistentKeyStore = persistentKeyStore,
            tlsProbe = tlsProbe,
            oauthDiscovery = oauthDiscovery,
        )

    fun fixtureCertSerials(
        trustManager: X509TrustManager,
        vararg fixtureCerts: X509Certificate,
    ): List<BigInteger> {
        val fixtureSerials = fixtureCerts.map { it.serialNumber }.toSet()
        return trustManager.acceptedIssuers
            .map { it.serialNumber }
            .filter { it in fixtureSerials }
    }

    fun successTlsProbe(): (URI, TlsContext) -> Unit = { _, _ -> }

    fun simulatingTlsProbe(
        serverCertificate: X509Certificate = TlsTestCertificates.caCertificate(),
        probedHosts: MutableList<String> = mutableListOf(),
        failTrustedProbeForHosts: Set<String> = emptySet(),
    ): (URI, TlsContext) -> Unit {
        val trustedProbeAttempts = mutableMapOf<String, Int>()
        return { uri, tlsContext ->
            probedHosts.add(uri.host)
            if (tlsContext.isCapturingProbe) {
                val capturingTrustManager = tlsContext.trustManager as CapturingTrustManager
                capturingTrustManager.checkServerTrusted(arrayOf(serverCertificate), "RSA")
                throw SSLHandshakeException("simulated capture probe failure")
            }
            if (tlsContext.usesSystemTrust) {
                throw SSLHandshakeException("simulated system trust probe failure")
            }
            if (uri.host in failTrustedProbeForHosts) {
                val attempts = trustedProbeAttempts.merge(uri.host, 1, Int::plus) ?: 1
                if (attempts == 1) {
                    throw SSLHandshakeException("simulated trusted probe failure")
                }
            }
        }
    }
}
