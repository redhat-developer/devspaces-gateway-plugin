/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package com.redhat.devtools.gateway.openshift

import com.redhat.devtools.gateway.auth.tls.CertificateSource
import com.redhat.devtools.gateway.auth.tls.SslContextFactory
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import io.kubernetes.client.util.KubeConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class OpenShiftClientBuilderTest {

    private val tlsContext = SslContextFactory.insecure()

    /** Self-signed RSA fixture for this suite only (*.invalid); not from any cluster or public CA. */
    // notsecret
    private val testClientCertPem = """
        -----BEGIN CERTIFICATE-----
        MIIDlTCCAn2gAwIBAgIUJ/MyNwdZC5vGYJMyYa5m4letZrYwDQYJKoZIhvcNAQEL
        BQAwWjEnMCUGA1UEAwweZmFrZS11bml0LXRlc3QuZXhhbXBsZS5pbnZhbGlkMSIw
        IAYDVQQKDBlFeGFtcGxlIFRlc3QgRml4dHVyZSBPbmx5MQswCQYDVQQGEwJYWDAe
        Fw0yNjA1MTMxMzE4MDJaFw0zNjA1MTAxMzE4MDJaMFoxJzAlBgNVBAMMHmZha2Ut
        dW5pdC10ZXN0LmV4YW1wbGUuaW52YWxpZDEiMCAGA1UECgwZRXhhbXBsZSBUZXN0
        IEZpeHR1cmUgT25seTELMAkGA1UEBhMCWFgwggEiMA0GCSqGSIb3DQEBAQUAA4IB
        DwAwggEKAoIBAQCG4CRbIkDOtpWzjWVW3V62FKzSfdAhdOJ/avqaPU2FiSjwEcBu
        VceoT5ilVjNWuDSqWeTrmwPjBfzywpB9OHrziqE5rRBnlyuxTMgxxbpNU8WEBFtn
        2RWvKen0uZOOLTro1oQsI6ALqKd07s8t9XjIZMEiOzhvKzYK6xQiqXjnYJqWAw3Z
        jhuvPcuvAALTXJMB6dASZNJ+q7gUd0gIMIjXVzAcj/QPxISwr3JMbpk+GvDnz0kF
        t7TFQRMqW56dbK36ukjDvLdFd+bbigE6m55vsGVdyZC55wBIB87ycn0zc3hgrfej
        4JVEqEhhlsifUkjGqNR2h9cdY3u58gzJwZP5AgMBAAGjUzBRMB0GA1UdDgQWBBSn
        488Oxr0rTEaI1Q3xHhxERrAZ5jAfBgNVHSMEGDAWgBSn488Oxr0rTEaI1Q3xHhxE
        RrAZ5jAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQAu0fWReMMg
        SMM2ctyslZ/b00FDUnDq713HQ+HH3sB28NVxvwKUHR637Z/VzX2HNlR5wuR2ulxK
        i6m54EBVCuE+T4kwPD/wx32RtGMAyuBlpamLC6WOdmVIVdYr66BRE7KdfTNnK+MJ
        Aa0duD5KniqxkdMU7ZxveHM6RRv/hDg0qybOxLSwetmfI9CRiw0qOGiX5PhCqsJV
        If1FxRl2mPPO0HiI94AyenmZfatuz9Y8Pb/q7cgdXpX2x29dnqXXO91qbVHk+zII
        sYowqsdnMTfqNHFSJGrNovvI63/GQ/8148oKAALaH4VgNOyVIdaKkPDR5I/WBnNm
        gJHFa/ozYnVi
        -----END CERTIFICATE-----
    """.trimIndent()

    // notsecret — PKCS#8 RSA key generated only for this test suite; not paired with a live cluster
    private val testClientKeyPem =
        "-----BEGIN PRIVATE KEY-----MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCcxRFWa06rNo5xsdpGTLsETLviFAR4wdB0bylr6lHCuNpdW1gM1TyRvVvFyQWbJK+Dk+emSV7ocbUibUaxdhWQ1W1Jv7L/s3H4zzYdWpOF4LZ+W0wHVhav4AZjiU7GbvO15uK2gbfuEZHJ06uLTpKMh5uWRGpEBr0eNDE1Y6au1lpZtJSfgXuJRXHd+kbngjtmjb4XcW/3xCBbcAcpmXSCGgE9uV5uuYVmCwYBrLtHK5MUKz0i1F85XN2DEQwAEHEkg5d9Z0ypxoKHMRGmBkoN2t9SihAU04efHHKWk2GTDFZGY4Ga4w/YmmhoPM54gU7ONRN3LYRfThr0Y5ivJfPTAgMBAAECggEACqQ97GCAXeg9fG5BjirAjybToiG7DqS+t7NMBoKeENpncurea+Xq+fb2odMRdFnl0sgEHio2LQ/QPIlaa8rDj/9M2d1kvdgP0SnlAdJs19ZMd6tO2o33dZzUEjGh4p++ygbli39RXZxdCcGP5Xbsmml3dZh99ibW85PrZd+2fYD9hsO0CTRdlCLB0/Gy/yKW4iujlJyp1HfPFeiw/lKL5GwNSFpMJElwGcQUPVdXPqU+GzPJH1m54jFlYzIZCXuHW4U99+NPFj6foA5PjMS3ZXcEyWZfhuHbDYrqj3aKxRURWaNdGVxML6xSmdXvN/4yo7CkLUr1PN0apKACVS0FYQKBgQDUTP0aTp+ja3TNVtrv4K1v8Me3stlbxR9zeB/zL4QBjSkALBhz8xeYGYm4i1elH3Ch9jn2rHy9E8C7zxwZbW2mYHtV/Micyc6X03yqBuEVzsqlIxSUoUKM4yVTlje5jj6ggo/OJP86wUExbvsxkjocjrRitqk5eAlt6KHr5SxbqQKBgQC9CewRCFBJQpYaHDEpyVHlsgM6qwP4W4VvStScTQ1hXnHE7g0mQhKxiS+WgF6RJkhiJhTvRfSVm0/3PSLa9woEtgiNx+cPscHLFvR0y4RCbjA1QDIGLbQV9/e6ntnlup4nFrCEgA17oQtb/EGXMAIRL2SdsGpd3YEWrSchOxuhGwKBgQDJeyt17Qo6OMAIJJbxswRGyXdxUm5QVtsLZgTEceLQ6hvwSukGGb3ZntsCZlPOpPDq9Nh7z6UueHGgi+U6CI1YqhZDO/1UN342vwKABrlVTgUqBgoBKK4VMXl6Q4UtN98dy+sYlCoZo9DwTkhc+k7mTVTKnlop7U7dnTsWuk+HyQKBgHOAm39wr/WDPMlpTlS00FhjIvv2v+9ApE/yzeNOZQ2IMkVcGia1GkzlgHEZsC5J0NI/aG0mNiIvCnYLIb/eT32/Z4yRhsmdF8aqGOU/8GjSgJwYxDfoNu9xWijppENsefNyNppOz24pYRJsF/tzdt/fMD/1KZh+ncAoPg9c2S3fAoGAWzYz9FFDIXv8yx8e5eGJstq+F2GkOrTliPfjX5PP1NkIJ8vFxGVE6RKzn8FSoE+Xxz5GjcULoE0hno7p2oYqLQpd7pI3LyLTSZhTN0FKDHQQpPtzoo6hSda53i3AaI0VO6mRi3VJaSoWhUkz/4ULR1NuuWpW2oFD2hIEQZqkiDI=-----END PRIVATE KEY-----"

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `TlsClientBuilder sets basePath for token auth`() {
        val client = TlsClientBuilder(
            server = "https://api.example.com:6443/",
            token = "test-token", // notsecret
            tlsContext = tlsContext,
        ).build()

        assertThat(client.basePath).isEqualTo("https://api.example.com:6443")
    }

    @Test
    fun `TlsClientBuilder sets basePath for client certificate auth`() {
        val client = TlsClientBuilder(
            server = "https://api.example.com:6443/",
            clientCert = CertificateSource.fromData(testClientCertPem),
            clientKey = CertificateSource.fromData(testClientKeyPem),
            tlsContext = tlsContext,
        ).build()

        assertThat(client.basePath).isEqualTo("https://api.example.com:6443")
    }

    @Test
    fun `TlsClientBuilder rejects missing auth`() {
        assertThatThrownBy {
            TlsClientBuilder(
                server = "https://api.example.com:6443",
                tlsContext = tlsContext,
            ).build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Provide either token OR clientCert + clientKey")
    }

    @Test
    fun `TlsClientBuilder rejects both token and client certificate`() {
        assertThatThrownBy {
            TlsClientBuilder(
                server = "https://api.example.com:6443",
            token = "test-token", // notsecret
                clientCert = CertificateSource.fromData(testClientCertPem),
                clientKey = CertificateSource.fromData(testClientKeyPem),
                tlsContext = tlsContext,
            ).build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Provide either token OR clientCert + clientKey")
    }

    @Test
    fun `TlsClientBuilder rejects client certificate without key`() {
        assertThatThrownBy {
            TlsClientBuilder(
                server = "https://api.example.com:6443",
                clientCert = CertificateSource.fromData(testClientCertPem),
                tlsContext = tlsContext,
            ).build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Provide either token OR clientCert + clientKey")
    }

    @Test
    fun `TokenClientBuilder applies read timeout`() {
        val client = TokenClientBuilder("https://api.example.com:6443", "test-token") // notsecret
            .readTimeout(45, TimeUnit.SECONDS)
            .build()

        assertThat(client.httpClient.readTimeoutMillis).isEqualTo(45_000)
    }

    @Test
    fun `TokenClientBuilder rejects empty token`() {
        assertThatThrownBy {
            TokenClientBuilder("https://api.example.com:6443", "")
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Provide either token OR clientCert + clientKey")
    }

    @Test
    fun `DefaultClientBuilder falls back when no kubeconfig files exist`() {
        val configUtils = mockk<KubeConfigUtils>()
        every { configUtils.getAllConfigFiles() } returns emptyList()

        runCatching { DefaultClientBuilder(configUtils).build() }

        verify(exactly = 1) { configUtils.getAllConfigFiles() }
        verify(exactly = 0) { configUtils.getAllConfigs(any()) }
    }

    @Test
    fun `DefaultClientBuilder falls back when kubeconfig merge fails`() {
        val configUtils = mockk<KubeConfigUtils>()
        val configPath = mockk<Path>()
        every { configUtils.getAllConfigFiles() } returns listOf(configPath)
        every { configUtils.getAllConfigs(listOf(configPath)) } throws RuntimeException("invalid yaml")

        runCatching { DefaultClientBuilder(configUtils).build() }

        verify(exactly = 1) { configUtils.getAllConfigFiles() }
        verify(exactly = 1) { configUtils.getAllConfigs(listOf(configPath)) }
        verify(exactly = 0) { configUtils.mergeConfigs(any()) }
    }

    @Test
    fun `DefaultClientBuilder builds from merged kubeconfig`() {
        val configUtils = mockk<KubeConfigUtils>()
        val configPath = mockk<Path>()
        val kubeConfig = KubeConfig(
            arrayListOf(
                mapOf(
                    "name" to "test-context",
                    "context" to mapOf(
                        "cluster" to "test-cluster",
                        "user" to "test-user",
                    ),
                ),
            ),
            arrayListOf(
                mapOf(
                    "name" to "test-cluster",
                    "cluster" to mapOf("server" to "https://merged.example.com:6443"),
                ),
            ),
            arrayListOf(
                mapOf(
                    "name" to "test-user",
                    "user" to mapOf("token" to "merged-token"), // notsecret
                ),
            ),
        )
        kubeConfig.setContext("test-context")

        every { configUtils.getAllConfigFiles() } returns listOf(configPath)
        every { configUtils.getAllConfigs(listOf(configPath)) } returns listOf(kubeConfig)
        every { configUtils.mergeConfigs(listOf(kubeConfig)) } returns kubeConfig

        val client = DefaultClientBuilder(configUtils).build()

        assertThat(client.basePath).isEqualTo("https://merged.example.com:6443")
    }
}
