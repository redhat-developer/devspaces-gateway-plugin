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

import com.redhat.devtools.gateway.auth.tls.TlsTrustManagerTestFixtures.API_SERVER_URL
import com.redhat.devtools.gateway.auth.tls.TlsTrustManagerTestFixtures.OAUTH_URL_1
import com.redhat.devtools.gateway.auth.tls.TlsTrustManagerTestFixtures.OAUTH_URL_2
import com.redhat.devtools.gateway.auth.tls.TlsTrustManagerTestFixtures.createManager
import com.redhat.devtools.gateway.auth.tls.TlsTrustManagerTestFixtures.kubeConfigWithCa
import com.redhat.devtools.gateway.auth.tls.TlsTrustManagerTestFixtures.kubeConfigWithInsecureSkip
import com.redhat.devtools.gateway.auth.tls.TlsTrustManagerTestFixtures.simulatingTlsProbe
import com.redhat.devtools.gateway.auth.tls.TlsTrustManagerTestFixtures.successTlsProbe
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import javax.net.ssl.X509TrustManager

class DefaultTlsTrustManagerTrustTest {

    private val serverCert = TlsTestCertificates.caCertificate()

    @Test
    fun `#createTlsContext honors kubeconfig insecure-skip-tls-verify`() {
        runBlocking {
            val manager = createManager(
                kubeConfigProvider = { listOf(kubeConfigWithInsecureSkip()) },
            )

            val tlsContext = manager.createTlsContext(
                API_SERVER_URL,
                decisionHandler = {
                    error("trust dialog must not be shown when insecure-skip-tls-verify is set")
                },
            )

            assertThat(tlsContext.isInsecure).isTrue()
        }
    }

    @Test
    fun `#createTlsContext uses wizard CA fast path without prompting`() {
        runBlocking {
            var prompted = false
            val manager = createManager(tlsProbe = successTlsProbe())

            manager.createTlsContext(
                API_SERVER_URL,
                decisionHandler = {
                    prompted = true
                    TlsTrustDecision.sessionOnly()
                },
                certificateAuthority = TlsTestCertificates.caSourceFromData(),
            )

            assertThat(prompted).isFalse()
        }
    }

    @Test
    fun `#createTlsContext uses JVM trust when no custom certificates are configured`() {
        runBlocking {
            var prompted = false
            val manager = createManager(tlsProbe = successTlsProbe())

            val tlsContext = manager.createTlsContext(
                API_SERVER_URL,
                decisionHandler = {
                    prompted = true
                    TlsTrustDecision.sessionOnly()
                },
            )

            assertThat(prompted).isFalse()
            assertThat(tlsContext.usesSystemTrust).isTrue()
        }
    }

    @Test
    fun `#createTlsContext prompts on unknown cert and stores session trust`() {
        runBlocking {
            val sessionStore = SessionTlsTrustStore()
            val manager = createManager(
                sessionTrustStore = sessionStore,
                tlsProbe = simulatingTlsProbe(serverCert),
            )

            manager.createTlsContext(
                API_SERVER_URL,
                decisionHandler = { TlsTrustDecision.sessionOnly() },
            )

            assertThat(sessionStore.get(API_SERVER_URL).map { it.serialNumber })
                .containsExactly(serverCert.serialNumber)
        }
    }

    @Test
    fun `#createTlsContext rejects when user rejects certificate`() {
        runBlocking {
            val manager = createManager(tlsProbe = simulatingTlsProbe(serverCert))

            val exception = kotlin.runCatching {
                manager.createTlsContext(
                    API_SERVER_URL,
                    decisionHandler = { TlsTrustDecision.reject() },
                )
            }.exceptionOrNull()

            assertThat(exception).isInstanceOf(TlsTrustRejectedException::class.java)
        }
    }

    @Test
    fun `#createOpenShiftTlsContext probes API and OAuth endpoints`() {
        runBlocking {
            val probedHosts = mutableListOf<String>()
            val manager = createManager(
                tlsProbe = simulatingTlsProbe(
                    serverCertificate = serverCert,
                    probedHosts = probedHosts,
                    failTrustedProbeForHosts = setOf("oauth.example.com", "oauth2.example.com"),
                ),
                oauthDiscovery = { _, _ -> listOf(OAUTH_URL_1, OAUTH_URL_2) },
            )

            manager.createOpenShiftTlsContext(
                API_SERVER_URL,
                decisionHandler = { TlsTrustDecision.sessionOnly() },
                certificateAuthority = TlsTestCertificates.caSourceFromData(),
            )

            assertThat(probedHosts).contains("api.example.com", "oauth.example.com", "oauth2.example.com")
        }
    }

    @Test
    fun `#createOpenShiftTlsContext OAuth prompt uses OAUTH endpoint kind`() {
        runBlocking {
            val prompted = mutableListOf<TlsServerCertificateInfo>()
            val manager = createManager(
                tlsProbe = simulatingTlsProbe(
                    serverCertificate = serverCert,
                    failTrustedProbeForHosts = setOf("oauth.example.com"),
                ),
                oauthDiscovery = { _, _ -> listOf(OAUTH_URL_1) },
            )

            manager.createOpenShiftTlsContext(
                API_SERVER_URL,
                decisionHandler = { info ->
                    prompted.add(info)
                    TlsTrustDecision.sessionOnly()
                },
                certificateAuthority = TlsTestCertificates.caSourceFromData(),
            )

            assertThat(prompted).hasSize(1)
            assertThat(prompted.single().endpointKind).isEqualTo(TlsEndpointKind.OAUTH)
            assertThat(prompted.single().serverUrl).isEqualTo(OAUTH_URL_1)
        }
    }

    @Test
    fun `#createOpenShiftTlsContext skips OAuth prompt when cert already trusted`() {
        runBlocking {
            val sessionStore = SessionTlsTrustStore().apply {
                put(OAUTH_URL_1, listOf(serverCert))
            }
            var prompted = false
            val manager = createManager(
                sessionTrustStore = sessionStore,
                tlsProbe = successTlsProbe(),
                oauthDiscovery = { _, _ -> listOf(OAUTH_URL_1) },
            )

            manager.createOpenShiftTlsContext(
                API_SERVER_URL,
                decisionHandler = {
                    prompted = true
                    TlsTrustDecision.sessionOnly()
                },
                certificateAuthority = TlsTestCertificates.caSourceFromData(),
            )

            assertThat(prompted).isFalse()
        }
    }

    @Test
    fun `#mergeTrustedContext includes wizard certificate authority`() {
        runBlocking {
            val manager = createManager()

            val tlsContext = manager.mergeTrustedContext(
                listOf(API_SERVER_URL),
                TlsTestCertificates.caSourceFromData(),
            )

            val trustedSerials = (tlsContext.trustManager as X509TrustManager)
                .acceptedIssuers
                .map { it.serialNumber }

            assertThat(trustedSerials).contains(serverCert.serialNumber)
        }
    }

    @Test
    fun `#mergeTrustedContext uses session trust when certificates already accepted`() {
        runBlocking {
            val sessionStore = SessionTlsTrustStore().apply {
                put(API_SERVER_URL, listOf(serverCert))
            }
            val manager = createManager(sessionTrustStore = sessionStore)

            val tlsContext = manager.mergeTrustedContext(
                listOf(API_SERVER_URL),
                TlsTestCertificates.caSourceFromData(),
            )

            val trustedSerials = (tlsContext.trustManager as X509TrustManager)
                .acceptedIssuers
                .map { it.serialNumber }

            assertThat(trustedSerials).containsExactly(serverCert.serialNumber)
        }
    }

    @Test
    fun `#mergeTrustedContext deduplicates same cert across API and OAuth URLs`() {
        runBlocking {
            val sessionStore = SessionTlsTrustStore().apply {
                put(API_SERVER_URL, listOf(serverCert))
                put(OAUTH_URL_1, listOf(serverCert))
            }
            val manager = createManager(sessionTrustStore = sessionStore)

            val tlsContext = manager.mergeTrustedContext(
                listOf(API_SERVER_URL, OAUTH_URL_1),
                certificateAuthority = null,
            )

            val trustedSerials = (tlsContext.trustManager as X509TrustManager)
                .acceptedIssuers
                .map { it.serialNumber }

            assertThat(trustedSerials).containsExactly(serverCert.serialNumber)
        }
    }

    @Test
    fun `#mergeTrustedContext prefers session trust over kubeconfig CA`() {
        runBlocking {
            val kubeCa = TlsTestCertificates.caCertificate()
            val sessionStore = SessionTlsTrustStore().apply {
                put(API_SERVER_URL, listOf(kubeCa))
            }
            val manager = createManager(
                kubeConfigProvider = { listOf(kubeConfigWithCa()) },
                sessionTrustStore = sessionStore,
            )

            val tlsContext = manager.mergeTrustedContext(
                listOf(API_SERVER_URL),
                TlsTestCertificates.caSourceFromData(),
            )

            val trustedSerials = (tlsContext.trustManager as X509TrustManager)
                .acceptedIssuers
                .map { it.serialNumber }

            assertThat(trustedSerials).containsExactly(kubeCa.serialNumber)
        }
    }
}
