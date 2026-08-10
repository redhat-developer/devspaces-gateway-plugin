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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

class TlsConnectionProbeRetryTest {

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
    fun `#connect propagates SSLHandshakeException after retrying a failed proxy`() {
        val tlsServer = TlsConnectionProbeTestFixtures.startTlsServer(ctx)
        val failProxy = TlsConnectionProbeTestFixtures.startAcceptThenCloseProxy(ctx)
        val selector = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(
                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", failProxy.localPort)),
                Proxy.NO_PROXY,
            )
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}
        }
        val uri = URI("https://127.0.0.1:${tlsServer.localPort}")

        val error = runCatching {
            TlsConnectionProbe.connect(
                uri,
                SslContextFactory.fromSystemTrust().sslContext,
                selector
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(SSLHandshakeException::class.java)
    }

    @Test
    fun `#connect does not try another proxy after SSLHandshakeException`() {
        val handshakeAttempts = AtomicInteger(0)
        val tlsServer = TlsConnectionProbeTestFixtures.startTlsServer(ctx, onHandshake = { handshakeAttempts.incrementAndGet() })
        val proxy = TlsConnectionProbeTestFixtures.startConnectProxy(ctx, tlsServer.localPort, requireAuth = false)
        val selector = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(
                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.localPort)),
                Proxy.NO_PROXY,
            )
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}
        }
        val uri = URI("https://127.0.0.1:${tlsServer.localPort}")

        val error = runCatching {
            TlsConnectionProbe.connect(
                uri,
                SslContextFactory.fromSystemTrust().sslContext,
                selector
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(SSLHandshakeException::class.java)
        assertThat(handshakeAttempts.get()).isEqualTo(1)
    }
}
