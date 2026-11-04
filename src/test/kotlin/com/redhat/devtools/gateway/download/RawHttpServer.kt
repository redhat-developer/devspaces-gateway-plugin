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
package com.redhat.devtools.gateway.download

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * A minimal HTTP/1.1 server on a raw socket for download tests: full control over
 * response headers and bodies, slow (chunked) sending, and - unlike
 * [com.sun.net.httpserver.HttpServer] - hard connection cuts in the middle of a response
 * body, which is what unstable networks (VPN, corporate proxies) do.
 */
internal class RawHttpServer(
    private val handler: (request: Request, requestCount: Int) -> Response,
) : AutoCloseable {

    data class Request(val path: String, val headers: Map<String, String>)

    data class Response(
        val status: String = "200 OK",
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
        /** When set, the connection is reset after this many body bytes were sent. */
        val cutBodyAfter: Int = -1,
        /** When set, the body is sent in chunks of this many bytes, slowing the sender down. */
        val chunkSize: Int = -1,
        /** Delay between body chunks. */
        val interChunkDelayMillis: Long = 0,
    )

    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val requestCount = AtomicInteger()

    /**
     * Connection threads and sockets that are serving right now. [close] stops them, so a test that
     * cancels mid-request does not leave a handler sleeping past its own end (the platform test
     * framework fails tests on leaked threads).
     */
    private val connectionThreads = CopyOnWriteArrayList<Thread>()
    private val connections = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var running = true

    fun start() {
        val started = CountDownLatch(1)
        acceptThread = thread(isDaemon = true, name = "raw-http-server-accept") {
            started.countDown()
            while (running) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                connections.add(socket)
                val connection = thread(isDaemon = true, name = "raw-http-server-connection") {
                    try {
                        handle(socket)
                    } finally {
                        connections.remove(socket)
                        connectionThreads.remove(Thread.currentThread())
                    }
                }
                connectionThreads.add(connection)
            }
        }
        started.await(5, TimeUnit.SECONDS)
    }

    fun uri(path: String): URI = URI("http://127.0.0.1:$port/$path")

    val port: Int get() = serverSocket.localPort

    @Suppress("SwallowedException") // a failed handler answers 500; an interrupted one is shutdown
    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                if (requestLine.isBlank()) return
                val path = requestLine.split(' ').getOrNull(1) ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isBlank()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                val response = try {
                    handler(Request(path, headers), requestCount.incrementAndGet())
                } catch (e: InterruptedException) {
                    throw e // close() interrupts a handler that waits (e.g. for response headers)
                } catch (e: Exception) {
                    Response(status = "500 Internal Server Error")
                }

                val out = s.getOutputStream()
                out.write("HTTP/1.1 ${response.status}\r\n".toByteArray())
                out.write("Content-Length: ${response.body.size}\r\n".toByteArray())
                out.write("Connection: close\r\n".toByteArray())
                response.headers.forEach { (name, value) -> out.write("$name: $value\r\n".toByteArray()) }
                out.write("\r\n".toByteArray())

                val bytesToSend = if (response.cutBodyAfter >= 0) {
                    min(response.cutBodyAfter, response.body.size)
                } else {
                    response.body.size
                }
                if (response.chunkSize > 0) {
                    var sent = 0
                    while (sent < bytesToSend) {
                        val chunk = min(response.chunkSize, bytesToSend - sent)
                        out.write(response.body, sent, chunk)
                        out.flush()
                        sent += chunk
                        if (response.interChunkDelayMillis > 0) Thread.sleep(response.interChunkDelayMillis)
                    }
                } else {
                    out.write(response.body, 0, bytesToSend)
                }
                out.flush()
                if (response.cutBodyAfter >= 0) {
                    // reset the connection instead of finishing the body
                    s.setSoLinger(true, 0)
                    return
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt() // close() stops a handler waiting in the test
        } catch (e: IOException) {
            // clients that cancel or reset mid-response are part of the test scenarios
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trim()
            line.append(byte.toChar())
        }
    }

    override fun close() {
        running = false
        runCatching { serverSocket.close() }
        // unblock reads/writes and handler waits before the test framework checks for leaked threads
        connections.forEach { runCatching { it.close() } }
        connectionThreads.forEach { it.interrupt() }
        acceptThread?.let { runCatching { it.join(CLOSE_TIMEOUT_MILLIS) } }
        connectionThreads.forEach { runCatching { it.join(CLOSE_TIMEOUT_MILLIS) } }
        connections.clear()
        connectionThreads.clear()
        acceptThread = null
    }

    private companion object {
        private const val CLOSE_TIMEOUT_MILLIS = 1_000L
    }
}
