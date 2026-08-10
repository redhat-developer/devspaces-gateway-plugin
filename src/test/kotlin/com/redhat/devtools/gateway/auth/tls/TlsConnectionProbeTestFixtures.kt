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

import java.io.InputStream
import java.io.OutputStream
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class TlsConnectionProbeTestContext {

    val executor: ExecutorService = Executors.newCachedThreadPool()
    val sockets = CopyOnWriteArrayList<AutoCloseable>()
    val tasks = CopyOnWriteArrayList<Future<*>>()
    var originalAuthenticator: Authenticator? = null

    fun captureAuthenticator() {
        originalAuthenticator = Authenticator.getDefault()
    }

    fun close() {
        tasks.forEach { it.cancel(true) }
        sockets.asReversed().forEach { runCatching { it.close() } }
        executor.shutdownNow()
        Authenticator.setDefault(originalAuthenticator)
    }
}

object TlsConnectionProbeTestFixtures {

    private val CHAR_ARRAY_EMPTY = CharArray(0)

    val SERVER_KEY_PEM = TlsConnectionProbeTestFixtures::class.java.classLoader
        .getResourceAsStream("tls/server-key.pem")!!.readBytes().toString(Charsets.UTF_8)

    val SERVER_CERT_PEM = TlsConnectionProbeTestFixtures::class.java.classLoader
        .getResourceAsStream("tls/server-cert.pem")!!.readBytes().toString(Charsets.UTF_8)

    val MISMATCH_KEY_PEM = TlsConnectionProbeTestFixtures::class.java.classLoader
        .getResourceAsStream("tls/mismatch-key.pem")!!.readBytes().toString(Charsets.UTF_8)

    val MISMATCH_CERT_PEM = TlsConnectionProbeTestFixtures::class.java.classLoader
        .getResourceAsStream("tls/mismatch-cert.pem")!!.readBytes().toString(Charsets.UTF_8)

    fun createServerSslContext(keyPem: String, certPem: String): SSLContext {
        val key = PemUtils.parsePrivateKey(keyPem)
        val cert = PemUtils.parseCertificate(certPem)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("server", key, CHAR_ARRAY_EMPTY, arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, CHAR_ARRAY_EMPTY)
        }
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, null)
        }
    }

    fun directSelector(): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) {}
    }

    fun httpProxySelector(proxyPort: Int): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> =
            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) {}
    }

    fun startTlsServer(
        ctx: TlsConnectionProbeTestContext,
        onHandshake: () -> Unit = {},
    ): SSLServerSocket {
        val sslContext = createServerSslContext(SERVER_KEY_PEM, SERVER_CERT_PEM)
        return startTlsServer(ctx, sslContext, onHandshake)
    }

    fun startMismatchCertServer(
        ctx: TlsConnectionProbeTestContext,
        onHandshake: () -> Unit = {},
    ): SSLServerSocket {
        val sslContext = createServerSslContext(MISMATCH_KEY_PEM, MISMATCH_CERT_PEM)
        return startTlsServer(ctx, sslContext, onHandshake)
    }

    private fun startTlsServer(
        ctx: TlsConnectionProbeTestContext,
        sslContext: SSLContext,
        onHandshake: () -> Unit,
    ): SSLServerSocket {
        val server = sslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        ctx.sockets += server
        ctx.tasks += ctx.executor.submit {
            while (!server.isClosed) {
                try {
                    (server.accept() as SSLSocket).use { client ->
                        client.soTimeout = 5_000
                        onHandshake()
                        client.startHandshake()
                        client.getInputStream().read()
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
        return server
    }

    fun startConnectProxy(
        ctx: TlsConnectionProbeTestContext,
        targetPort: Int,
        requireAuth: Boolean,
        unauthorizedAttempts: AtomicInteger = AtomicInteger(0),
    ): ServerSocket {
        val server = ServerSocket(0)
        ctx.sockets += server
        ctx.tasks += ctx.executor.submit {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    ctx.tasks += ctx.executor.submit {
                        handleConnectClient(ctx, client, targetPort, requireAuth, unauthorizedAttempts)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
        return server
    }

    fun startConnectAuthorityCapturingProxy(
        ctx: TlsConnectionProbeTestContext,
        capturedConnectAuthority: AtomicReference<String>,
    ): ServerSocket {
        val server = ServerSocket(0)
        ctx.sockets += server
        ctx.tasks += ctx.executor.submit {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    ctx.tasks += ctx.executor.submit {
                        handleConnectAuthorityCapture(client, capturedConnectAuthority)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
        return server
    }

    fun startAcceptThenCloseProxy(ctx: TlsConnectionProbeTestContext): ServerSocket {
        val server = ServerSocket(0)
        ctx.sockets += server
        ctx.tasks += ctx.executor.submit {
            try {
                server.accept().close()
            } catch (_: Exception) {}
        }
        return server
    }

    fun startMalformedConnectProxy(ctx: TlsConnectionProbeTestContext): ServerSocket {
        val server = ServerSocket(0)
        ctx.sockets += server
        ctx.tasks += ctx.executor.submit {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    ctx.tasks += ctx.executor.submit {
                        handleMalformedConnectClient(client)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
        return server
    }

    private fun handleConnectClient(
        ctx: TlsConnectionProbeTestContext,
        client: Socket,
        targetPort: Int,
        requireAuth: Boolean,
        unauthorizedAttempts: AtomicInteger,
    ) {
        client.use { proxyClient ->
            proxyClient.soTimeout = 5_000
            val request = readHttpHeaders(proxyClient.getInputStream())
            val authorized = request.lines().any {
                it.startsWith("Proxy-Authorization: Basic ", ignoreCase = true)
            }
            if (requireAuth && !authorized) {
                unauthorizedAttempts.incrementAndGet()
                proxyClient.getOutputStream().write(
                    "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\n\r\n"
                        .toByteArray(StandardCharsets.US_ASCII)
                )
                proxyClient.getOutputStream().flush()
                return
            }
            if (!request.startsWith("CONNECT ")) {
                proxyClient.getOutputStream().write(
                    "HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
                )
                return
            }
            Socket("127.0.0.1", targetPort).use { origin ->
                proxyClient.getOutputStream().write(
                    "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
                )
                proxyClient.getOutputStream().flush()
                val up = ctx.executor.submit { copy(proxyClient.getInputStream(), origin.getOutputStream()) }
                try {
                    copy(origin.getInputStream(), proxyClient.getOutputStream())
                } finally {
                    up.cancel(true)
                }
            }
        }
    }

    private fun handleConnectAuthorityCapture(
        client: Socket,
        capturedConnectAuthority: AtomicReference<String>,
    ) {
        client.use { proxyClient ->
            proxyClient.soTimeout = 5_000
            val request = readHttpHeaders(proxyClient.getInputStream())
            if (!request.startsWith("CONNECT ")) {
                proxyClient.getOutputStream().write(
                    "HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
                )
                return
            }
            capturedConnectAuthority.set(request.lineSequence().first().connectAuthorityFromRequestLine())
            proxyClient.getOutputStream().write(
                "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
            )
            proxyClient.getOutputStream().flush()
        }
    }

    private fun handleMalformedConnectClient(client: Socket) {
        client.use { proxyClient ->
            proxyClient.soTimeout = 5_000
            val request = readHttpHeaders(proxyClient.getInputStream())
            if (!request.startsWith("CONNECT ")) {
                proxyClient.getOutputStream().write(
                    "HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
                )
                return
            }
            val headers = buildString {
                append("HTTP/1.1 200 Connection Established\r\n")
                repeat(TlsConnectionProbe.MAX_HEADER_LINES + 1) { i ->
                    append("X-Custom-Header-$i: value\r\n")
                }
            }
            proxyClient.getOutputStream().write(headers.toByteArray(StandardCharsets.US_ASCII))
            proxyClient.getOutputStream().flush()
        }
    }

    fun readHttpHeaders(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            bytes += b.toByte()
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> break
                else -> 0
            }
        }
        return String(bytes.toByteArray(), StandardCharsets.US_ASCII)
    }

    fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(8_192)
        while (true) {
            val n = try {
                from.read(buffer)
            } catch (_: Exception) {
                -1
            }
            if (n < 0) break
            to.write(buffer, 0, n)
            to.flush()
        }
    }

    private fun String.connectAuthorityFromRequestLine(): String =
        removePrefix("CONNECT ").substringBefore(" HTTP/")
}
