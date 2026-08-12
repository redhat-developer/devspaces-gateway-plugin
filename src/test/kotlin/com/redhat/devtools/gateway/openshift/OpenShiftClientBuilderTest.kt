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
import com.redhat.devtools.gateway.auth.tls.TlsTestCertificates
import com.redhat.devtools.gateway.openshift.apiclient.ClientCertClientBuilder
import com.redhat.devtools.gateway.openshift.apiclient.LinkClientBuilder
import com.redhat.devtools.gateway.openshift.apiclient.TokenClientBuilder
import com.redhat.devtools.gateway.auth.tls.SslContextFactory
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import io.kubernetes.client.util.KubeConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import com.redhat.devtools.gateway.util.IdeHttpProxy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class OpenShiftClientBuilderTest {

    private val tlsContext = SslContextFactory.insecure()

    /** Self-signed RSA fixture from [TlsTestCertificates]; not from any cluster or public CA. */
    private val testClientCertPem = TlsTestCertificates.CA_PEM

    private val testClientKeyPem = TlsTestCertificates.CLIENT_KEY_PEM

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `TokenClientBuilder sets basePath`() {
        val client = TokenClientBuilder(
            server = "https://api.example.com:6443/",
            token = "test-token", // notsecret
            tlsContext = tlsContext,
        ).build()

        assertThat(client.basePath).isEqualTo("https://api.example.com:6443")
    }

    @Test
    fun `ClientCertClientBuilder sets basePath`() {
        val client = ClientCertClientBuilder(
            server = "https://api.example.com:6443/",
            clientCert = CertificateSource.fromData(testClientCertPem),
            clientKey = CertificateSource.fromData(testClientKeyPem),
            tlsContext = tlsContext,
        ).build()

        assertThat(client.basePath).isEqualTo("https://api.example.com:6443")
    }

    @Test
    fun `TokenClientBuilder applies read timeout`() {
        val client = TokenClientBuilder("https://api.example.com:6443", "test-token", tlsContext) // notsecret
            .readTimeout(45, TimeUnit.SECONDS)
            .build()

        assertThat(client.httpClient.readTimeoutMillis).isEqualTo(45_000)
    }

    @Test
    fun `TokenClientBuilder OkHttpClient uses IDE proxy authenticator`() {
        val client = TokenClientBuilder(
            server = "https://api.example.com:6443",
            token = "test-token", // notsecret
            tlsContext = tlsContext,
        ).build()
        assertThat(client.httpClient.proxySelector).isNotNull
        assertThat(client.httpClient.proxyAuthenticator)
            .isSameAs(IdeHttpProxy.PROXY_AUTHENTICATOR)
    }

    @Test
    fun `TokenClientBuilder rejects empty token`() {
        assertThatThrownBy {
            TokenClientBuilder("https://api.example.com:6443", "", tlsContext)
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Provide either token OR clientCert + clientKey")
    }

    @Test
    fun `LinkClientBuilder falls back when no kubeconfig files exist`() {
        val configUtils = mockk<KubeConfigUtils>()
        every { configUtils.getAllConfigFiles() } returns emptyList()

        runCatching { LinkClientBuilder(configUtils).build() }

        verify(exactly = 1) { configUtils.getAllConfigFiles() }
        verify(exactly = 0) { configUtils.getAllConfigs(any()) }
    }

    @Test
    fun `LinkClientBuilder falls back when kubeconfig merge fails`() {
        val configUtils = mockk<KubeConfigUtils>()
        val configPath = mockk<Path>()
        every { configUtils.getAllConfigFiles() } returns listOf(configPath)
        every { configUtils.getAllConfigs(listOf(configPath)) } throws RuntimeException("invalid yaml")

        runCatching { LinkClientBuilder(configUtils).build() }

        verify(exactly = 1) { configUtils.getAllConfigFiles() }
        verify(exactly = 1) { configUtils.getAllConfigs(listOf(configPath)) }
        verify(exactly = 0) { configUtils.mergeConfigs(any()) }
    }

    @Test
    fun `LinkClientBuilder builds from merged kubeconfig`() {
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

        val client = LinkClientBuilder(configUtils).build()

        assertThat(client.basePath).isEqualTo("https://merged.example.com:6443")
    }
}
