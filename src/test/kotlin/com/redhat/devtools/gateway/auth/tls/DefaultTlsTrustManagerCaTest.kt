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
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import javax.net.ssl.X509TrustManager

class DefaultTlsTrustManagerCaTest {

    private val serverUrl = "https://api.example.com:6443"

    private fun createManager(
        sessionTrustStore: SessionTlsTrustStore = SessionTlsTrustStore(),
    ): DefaultTlsTrustManager {
        val persistentPath = Files.createTempDirectory("tls-trust").resolve("truststore.p12")
        return DefaultTlsTrustManager(
            kubeConfigProvider = { emptyList() },
            kubeConfigWriter = { _, _ -> },
            sessionTrustStore = sessionTrustStore,
            persistentKeyStore = PersistentKeyStore(persistentPath),
        )
    }

    @Test
    fun `#mergedContextFor includes wizard certificate authority`() {
        runBlocking {
        val expectedCert = TlsTestCertificates.caCertificate()
        val manager = createManager()

        val tlsContext = manager.mergedContextFor(
            listOf(serverUrl),
            TlsTestCertificates.caSourceFromData(),
        )

        val trustedSerials = (tlsContext.trustManager as X509TrustManager)
            .acceptedIssuers
            .map { it.serialNumber }

        assertThat(trustedSerials).contains(expectedCert.serialNumber)
        }
    }

    @Test
    fun `#mergedContextFor uses session trust when certificates already accepted`() {
        runBlocking {
        val sessionCert = TlsTestCertificates.caCertificate()
        val sessionStore = SessionTlsTrustStore().apply {
            put(serverUrl, listOf(sessionCert))
        }
        val manager = createManager(sessionTrustStore = sessionStore)

        val tlsContext = manager.mergedContextFor(
            listOf(serverUrl),
            TlsTestCertificates.caSourceFromData(),
        )

        val trustedSerials = (tlsContext.trustManager as X509TrustManager)
            .acceptedIssuers
            .map { it.serialNumber }

        assertThat(trustedSerials).containsExactly(sessionCert.serialNumber)
        }
    }

    @Test
    fun `#ensureOpenShiftTlsContext honors kubeconfig insecure-skip-tls-verify`() {
        runBlocking {
            val serverUrl = "https://api.example.com:6443"
            val kubeConfig = KubeConfigTestHelpers.createMockKubeConfig(
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
            val manager = DefaultTlsTrustManager(
                kubeConfigProvider = { listOf(kubeConfig) },
                kubeConfigWriter = { _, _ -> },
                sessionTrustStore = SessionTlsTrustStore(),
                persistentKeyStore = PersistentKeyStore(
                    Files.createTempDirectory("tls-trust-insecure").resolve("truststore.p12"),
                ),
            )

            val tlsContext = manager.ensureOpenShiftTlsContext(
                serverUrl,
                decisionHandler = {
                    error("trust dialog must not be shown when insecure-skip-tls-verify is set")
                },
            )

            val trustManager = tlsContext.trustManager as X509TrustManager
            trustManager.checkServerTrusted(emptyArray(), "RSA")
            assertThat(trustManager.acceptedIssuers).isEmpty()
        }
    }
}
