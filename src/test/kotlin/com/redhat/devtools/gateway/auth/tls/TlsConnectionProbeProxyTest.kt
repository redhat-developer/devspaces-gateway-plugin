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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TlsConnectionProbeProxyTest {

    private val ctx = TlsConnectionProbeTestContext()

    @BeforeEach
    fun setUp() {
        ctx.captureAuthenticator()
    }

    @AfterEach
    fun tearDown() {
        ctx.close()
    }

    @Test
    fun `#connect succeeds via HTTP CONNECT proxy`() {
        val tlsServer = TlsConnectionProbeTestFixtures.startTlsServer(ctx)
        val proxy = TlsConnectionProbeTestFixtures.startConnectProxy(ctx, tlsServer.localPort, requireAuth = false)
        val uri = URI("https://127.0.0.1:${tlsServer.localPort}")

        assertThatCode {
            TlsConnectionProbe.connect(
                uri,
                SslContextFactory.insecure().sslContext,
                TlsConnectionProbeTestFixtures.httpProxySelector(proxy.localPort)
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `#connect fails when CONNECT headers are unterminated`() {
        val tlsServer = TlsConnectionProbeTestFixtures.startTlsServer(ctx)
        val proxy = TlsConnectionProbeTestFixtures.startMalformedConnectProxy(ctx)
        val uri = URI("https://127.0.0.1:${tlsServer.localPort}")

        val error = runCatching {
            TlsConnectionProbe.connect(
                uri,
                SslContextFactory.insecure().sslContext,
                TlsConnectionProbeTestFixtures.httpProxySelector(proxy.localPort)
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
            .hasMessageContaining("TLS probe failed")
        val cause = error?.cause
        assertThat(cause).isInstanceOf(IOException::class.java)
            .hasMessageContaining("CONNECT response headers exceed limit or are unterminated")
    }

    @Test
    fun `#connect retries CONNECT with Basic proxy auth after 407`() {
        val tlsServer = TlsConnectionProbeTestFixtures.startTlsServer(ctx)
        val unauthorizedAttempts = AtomicInteger(0)
        val proxy = TlsConnectionProbeTestFixtures.startConnectProxy(ctx, tlsServer.localPort, requireAuth = true, unauthorizedAttempts)
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
                PasswordAuthentication("proxy-user", "proxy-pass".toCharArray())
        })
        val uri = URI("https://127.0.0.1:${tlsServer.localPort}")

        assertThatCode {
            TlsConnectionProbe.connect(
                uri,
                SslContextFactory.insecure().sslContext,
                TlsConnectionProbeTestFixtures.httpProxySelector(proxy.localPort)
            )
        }.doesNotThrowAnyException()
        assertThat(unauthorizedAttempts.get()).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `#connect sends bracketed authority for IPv6 target`() {
        val capturedAuthority = AtomicReference<String>()
        val proxy = TlsConnectionProbeTestFixtures.startConnectAuthorityCapturingProxy(ctx, capturedAuthority)
        val uri = URI.create("https://[::1]:443")

        runCatching {
            TlsConnectionProbe.connect(
                uri,
                SslContextFactory.insecure().sslContext,
                TlsConnectionProbeTestFixtures.httpProxySelector(proxy.localPort),
            )
        }

        assertThat(capturedAuthority.get()).isEqualTo("[::1]:443")
    }
}
