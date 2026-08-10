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

import com.redhat.devtools.gateway.util.IdeHttpProxy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Probes TLS connectivity to a remote server. Its sole purpose is to verify the server's
 * certificate is trusted. A successful handshake confirms the connection path works and the
 * certificate is accepted. No application data is exchanged; only the handshake matters.
 *
 * An [SSLException] (e.g. untrusted certificate) surfaces to the caller for trust capture.
 */
object TlsConnectionProbe {

    private const val DEFAULT_HTTPS_PORT = 443
    private const val TIMEOUT_MS = 30_000
    private const val MAX_LINE_BYTES = 8 * 1024
    internal const val MAX_HEADER_LINES = 100

    /**
     * Establishes a TLS connection to [serverUri] to verify the server's certificate is trusted.
     *
     * Iterates through proxies selected by [proxySelector], falling back to direct connection
     * if none are available. Attempts each proxy in order until one succeeds.
     *
     * @throws IOException if all proxy attempts fail to establish a connection
     * @throws SSLException if the TLS handshake fails (e.g. untrusted certificate), for trust capture
     */
    fun connect(
        serverUri: URI,
        sslContext: SSLContext,
        proxySelector: ProxySelector = IdeHttpProxy.proxySelector(),
    ) {
        val host = serverUri.host
            ?: throw IOException("TLS probe URL has no host: $serverUri")
        val port = if (serverUri.port != -1) {
                serverUri.port
            } else {
                DEFAULT_HTTPS_PORT
            }
        val selectUri = URI("https", null, host, port, null, null, null)
        val proxies = proxySelector.select(selectUri)
            .ifEmpty { listOf(Proxy.NO_PROXY) }

        var lastException: IOException? = null
        val connected = proxies.any { proxy ->
            try {
                connect(host, port, sslContext, proxy)
                true
            } catch (e: SSLException) {
                // Handshake / TLS failures must surface for trust capture; do not try another proxy.
                throw e
            } catch (e: IOException) {
                lastException = e
                false
            }
        }
        if (!connected) {
            throw IOException("TLS probe failed for $host:$port", lastException)
        }
    }

    private fun connect(host: String, port: Int, sslContext: SSLContext, proxy: Proxy) {
        when (proxy.type()) {
            Proxy.Type.HTTP -> connectViaHttpProxy(host, port, sslContext, proxy)
            Proxy.Type.SOCKS -> connectViaSocksOrDirect(host, port, sslContext, proxy)
            else -> connectViaSocksOrDirect(host, port, sslContext, Proxy.NO_PROXY)
        }
    }

    /**
     * Establishes an HTTP CONNECT tunnel through the proxy. Tries unauthenticated first,
     * then retries with Basic auth if the proxy responds with 407. Only Basic auth is
     * supported; Kerberos is not.
     */
    private fun connectViaHttpProxy(host: String, port: Int, sslContext: SSLContext, proxy: Proxy) {
        val proxyAddress = proxy.address() as? InetSocketAddress
            ?: throw IOException("HTTP proxy has no InetSocketAddress")
        openTunneledSocket(host, port, proxyAddress, proxyAuthorization = null).use { plain ->
            val status = readConnectStatus(plain.getInputStream())
            if (status == 200) {
                handshakeOver(plain, host, port, sslContext)
                return
            }
            if (status != 407) {
                throw IOException("HTTP CONNECT to $host:$port via $proxyAddress failed with status $status")
            }
        }

        val authHeader = basicProxyAuthorization(proxyAddress, host, port)
            ?: throw IOException("HTTP proxy $proxyAddress requires authentication (407)")
        openTunneledSocket(host, port, proxyAddress, authHeader).use { plain ->
            val status = readConnectStatus(plain.getInputStream())
            if (status != 200) {
                throw IOException("HTTP CONNECT to $host:$port via $proxyAddress failed with status $status")
            }
            handshakeOver(plain, host, port, sslContext)
        }
    }

    private fun openTunneledSocket(
        host: String,
        port: Int,
        proxyAddress: InetSocketAddress,
        proxyAuthorization: String?,
    ): Socket {
        val socket = Socket()
        socket.soTimeout = TIMEOUT_MS
        socket.connect(InetSocketAddress(proxyAddress.hostString, proxyAddress.port), TIMEOUT_MS)
        try {
            writeConnectRequest(socket, host, port, proxyAuthorization)
            return socket
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    private fun connectViaSocksOrDirect(host: String, port: Int, sslContext: SSLContext, proxy: Proxy) {
        Socket(proxy).use { plain ->
            plain.soTimeout = TIMEOUT_MS
            plain.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            handshakeOver(plain, host, port, sslContext)
        }
    }

    private fun writeConnectRequest(plain: Socket, host: String, port: Int, proxyAuthorization: String?) {
        val authority = connectAuthority(host, port)
        val request = buildString {
            append("CONNECT $authority HTTP/1.1\r\n")
            append("Host: $authority\r\n")
            if (proxyAuthorization != null) {
                append("Proxy-Authorization: $proxyAuthorization\r\n")
            }
            append("\r\n")
        }
        plain.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
        plain.getOutputStream().flush()
    }

    /**
     * Builds the authority string for an HTTP CONNECT request (e.g. `example.com:443`).
     * Supports IPv4 and IPv6 hosts.
     *
     * [URI.host] strips brackets from IPv6 addresses, returning the raw address (e.g. `::1`).
     * The `if` branch detects IPv6 by checking for `:` in the host: IPv6 addresses contain
     * colons and require bracket notation in the authority (`[::1]:443`). The `else` branch
     * handles IPv4 addresses and hostnames, which use plain `host:port` form.
     */
    private fun connectAuthority(host: String, port: Int): String {
        val bareHost = host.trim('[', ']')
        return if (bareHost.contains(':')) {
            // IPv6
            "[$bareHost]:$port"
        } else {
            // IPv4
            "$bareHost:$port"
        }
    }

    /**
     * Reads the CONNECT response status and headers one byte at a time so we do not
     * buffer TLS handshake bytes that follow a successful tunnel.
     */
    private fun readConnectStatus(input: InputStream): Int {
        val statusLine = readAsciiLine(input)
            ?: throw IOException("HTTP CONNECT closed with no response")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("Malformed CONNECT response: $statusLine")
        repeat(MAX_HEADER_LINES) {
            val line = readAsciiLine(input)
                ?: throw IOException("CONNECT response closed before header terminator")
            if (line.isEmpty()) {
                return status
            }
        }
        throw IOException("CONNECT response headers exceed limit or are unterminated")
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.US_ASCII)
            }
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, StandardCharsets.US_ASCII)
            }
            if (buffer.size() >= MAX_LINE_BYTES) {
                throw IOException("CONNECT response line exceeds $MAX_LINE_BYTES bytes")
            }
            buffer.write(b)
        }
    }

    /**
     * Requests Basic auth credentials for the proxy via [Authenticator.requestPasswordAuthentication].
     *
     * Handles the case where IDE [ProxySelector]s return unresolved addresses.
     *
     * Returns a `"Basic <base64>"` header string, or `null` if no credentials are available.
     * Clears the password from memory after use.
     */
    private fun basicProxyAuthorization(proxyAddress: InetSocketAddress, host: String, port: Int): String? {
        // IDE ProxySelectors often return unresolved addresses (address == null).
        val proxyInetAddress = proxyAddress.address
            ?: runCatching { InetAddress.getByName(proxyAddress.hostString) }.getOrNull()
        val auth = Authenticator.requestPasswordAuthentication(
            proxyAddress.hostString,
            proxyInetAddress,
            proxyAddress.port,
            "https",
            "",
            "Basic",
            URI("https", null, host, port, null, null, null).toURL(),
            Authenticator.RequestorType.PROXY,
        ) ?: return null
        val password = auth.password
        return try {
            val token = Base64.getEncoder().encodeToString(
                "${auth.userName}:${String(password)}".toByteArray(StandardCharsets.ISO_8859_1)
            )
            "Basic $token"
        } finally {
            password.fill('\u0000')
        }
    }

    private fun handshakeOver(plain: Socket, host: String, port: Int, sslContext: SSLContext) {
        val sslSocket = sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        sslSocket.soTimeout = TIMEOUT_MS
        sslSocket.sslParameters = sslSocket.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
        }
        sslSocket.use { it.startHandshake() }
    }
}
