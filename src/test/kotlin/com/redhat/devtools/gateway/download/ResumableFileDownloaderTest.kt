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

import com.redhat.devtools.gateway.util.ExponentialBackoff
import com.redhat.devtools.gateway.util.Sha256Checksum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.http.HttpClient
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ResumableFileDownloaderTest {

    @JvmField
    @TempDir
    var tempDir: Path? = null

    private val servers = CopyOnWriteArrayList<RawHttpServer>()

    /** Headless stand-in for the platform's shared scheduled executor. */
    private val watchdogScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "test-download-watchdog").apply { isDaemon = true }
    }

    @AfterEach
    fun afterEach() {
        servers.forEach(RawHttpServer::close)
        watchdogScheduler.shutdownNow()
    }

    private fun downloader(maxAttempts: Int = 15) = ResumableFileDownloader(
        httpClient = HttpClient.newHttpClient(),
        maxZeroProgressAttempts = maxAttempts,
        backoffFactory = { ExponentialBackoff(initialMillis = 1, maxMillis = 2) },
        watchdogSchedulerFactory = { watchdogScheduler },
    )

    @Test
    fun `downloads a complete file`() = runTest {
        val content = bytes(256 * 1024)
        val server = server { _, _ -> RawHttpServer.Response(body = content) }
        val target = target("file.bin")

        downloader().download(server.uri("file.bin"), target)

        assertThat(target).hasBinaryContent(content)
        assertThat(partFile(target)).doesNotExist()
    }

    @Test
    fun `resumes a download that is cut mid-body`() = runTest {
        val content = bytes(300 * 1024)
        val rangeRequests = CopyOnWriteArrayList<String?>()
        val server = server { request, _ ->
            val range = request.headers["range"]
            rangeRequests.add(range)
            if (range == null) {
                // the very same cut a VPN produces: headers + part of the body, then the connection dies
                RawHttpServer.Response(body = content, cutBodyAfter = 100 * 1024)
            } else {
                val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                RawHttpServer.Response(
                    status = "206 Partial Content",
                    headers = mapOf("Content-Range" to "bytes $start-${content.size - 1}/${content.size}"),
                    body = content.copyOfRange(start, content.size),
                )
            }
        }
        val target = target("file.bin")

        downloader().download(server.uri("file.bin"), target)

        assertThat(target).hasBinaryContent(content)
        assertThat(rangeRequests).isNotEmpty
    }

    @Test
    fun `restarts from scratch when the server ignores Range`() = runTest {
        val content = bytes(200 * 1024)
        var requests = 0
        val server = server { _, _ ->
            requests++
            if (requests == 1) {
                RawHttpServer.Response(body = content, cutBodyAfter = 10 * 1024)
            } else {
                // answers the Range request with 200 and the whole body
                RawHttpServer.Response(body = content)
            }
        }
        val target = target("file.bin")

        downloader().download(server.uri("file.bin"), target)

        assertThat(target).hasBinaryContent(content)
    }

    @Test
    fun `restarts from scratch after HTTP 416`() = runTest {
        val content = bytes(100 * 1024)
        var requests = 0
        val server = server { request, _ ->
            requests++
            if (requests == 1) {
                RawHttpServer.Response(body = content, cutBodyAfter = 10 * 1024)
            } else if (request.headers["range"] != null) {
                RawHttpServer.Response(status = "416 Range Not Satisfiable")
            } else {
                RawHttpServer.Response(body = content)
            }
        }
        val target = target("file.bin")

        downloader().download(server.uri("file.bin"), target)

        assertThat(target).hasBinaryContent(content)
    }

    @Test
    fun `gives up after attempts without any progress`() = runTest {
        val server = server { _, _ -> RawHttpServer.Response(status = "500 Internal Server Error") }
        val target = target("file.bin")

        val e = assertThrows<Exception> {
            downloader(maxAttempts = 3).download(server.uri("file.bin"), target)
        }
        assertThat(e).hasMessageContaining("after 3 attempts")
        assertThat(target).doesNotExist()
    }

    @Test
    fun `fails fast on HTTP 404`() = runTest {
        val server = server { _, _ -> RawHttpServer.Response(status = "404 Not Found") }
        val target = target("file.bin")

        assertThrows<PermanentDownloadException> {
            downloader().download(server.uri("file.bin"), target)
        }
        assertThat(target).doesNotExist()
    }

    @Test
    fun `retries on HTTP 429`() = runTest {
        val content = bytes(64 * 1024)
        val requests = AtomicInteger()
        val server = server { _, _ ->
            if (requests.incrementAndGet() == 1) RawHttpServer.Response(status = "429 Too Many Requests")
            else RawHttpServer.Response(body = content)
        }
        val target = target("file.bin")

        downloader().download(server.uri("file.bin"), target)

        assertThat(target).hasBinaryContent(content)
        assertThat(requests.get()).isEqualTo(2)
    }

    @Test
    fun `retries on HTTP 408`() = runTest {
        val content = bytes(64 * 1024)
        val requests = AtomicInteger()
        val server = server { _, _ ->
            if (requests.incrementAndGet() == 1) RawHttpServer.Response(status = "408 Request Timeout")
            else RawHttpServer.Response(body = content)
        }
        val target = target("file.bin")

        downloader().download(server.uri("file.bin"), target)

        assertThat(target).hasBinaryContent(content)
        assertThat(requests.get()).isEqualTo(2)
    }

    @Test
    fun `re-downloads once when the checksum does not match`() = runTest {
        val goodContent = bytes(64 * 1024)
        val badContent = bytes(64 * 1024, seed = 42)
        val requests = AtomicInteger()
        val server = server { _, _ ->
            if (requests.incrementAndGet() == 1) RawHttpServer.Response(body = badContent)
            else RawHttpServer.Response(body = goodContent)
        }
        val target = target("file.bin")

        downloader().download(
            server.uri("file.bin"),
            target,
            expectedChecksum = Sha256Checksum(sha256(goodContent)),
        )

        assertThat(target).hasBinaryContent(goodContent)
        assertThat(requests.get()).isEqualTo(2)
    }

    @Test
    fun `fails when the checksum never matches`() = runTest {
        val server = server { _, _ -> RawHttpServer.Response(body = bytes(32 * 1024)) }
        val target = target("file.bin")

        assertThrows<PermanentDownloadException> {
            downloader().download(
                server.uri("file.bin"),
                target,
                expectedChecksum = Sha256Checksum(sha256(bytes(32 * 1024, seed = 99))),
            )
        }
        assertThat(target).doesNotExist()
    }

    @Test
    fun `propagates cancellation from checkCancelled`() = runTest {
        val server = server { _, _ -> RawHttpServer.Response(body = bytes(4 * 1024 * 1024)) }
        val target = target("file.bin")
        var cancellationChecks = 0

        assertThrows<kotlinx.coroutines.CancellationException> {
            downloader().download(
                server.uri("file.bin"),
                target,
                checkCancelled = {
                    if (++cancellationChecks >= 3) throw kotlinx.coroutines.CancellationException("cancelled")
                },
            )
        }
        assertThat(target).doesNotExist()
    }

    @Test
    fun `propagates cancellation while waiting for response headers`() = runTest {
        val requestReceived = CountDownLatch(1)
        val server = server { _, _ ->
            // delay the response headers so the client blocks in HttpClient.send
            requestReceived.countDown()
            Thread.sleep(TimeUnit.SECONDS.toMillis(10))
            RawHttpServer.Response(body = ByteArray(16))
        }
        val target = target("file.bin")

        val download = launch(Dispatchers.IO) {
            downloader().download(server.uri("file.bin"), target)
        }
        assertThat(requestReceived.await(5, TimeUnit.SECONDS)).isTrue

        download.cancel()
        download.join()

        assertThat(download.isCancelled).isTrue
        assertThat(target).doesNotExist()
    }

    @Test
    fun `reports download progress`() = runTest {
        val content = bytes(3 * 1024 * 1024)
        val server = server { _, _ -> RawHttpServer.Response(body = content) }
        val target = target("file.bin")
        val progress = CopyOnWriteArrayList<ResumableFileDownloader.Progress>()

        downloader().download(server.uri("file.bin"), target, onProgress = { progress.add(it) })

        assertThat(progress).isNotEmpty
        assertThat(progress.last().totalBytes).isEqualTo(content.size.toLong())
        assertThat(progress.last().downloadedBytes).isEqualTo(content.size.toLong())
    }

    // ---------------------------------------------------------------------------------------------

    private fun bytes(size: Int, seed: Int = 7): ByteArray = ByteArray(size) { ((it + seed) % 251).toByte() }

    private fun sha256(content: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it) }

    private fun target(name: String): Path = tempDir!!.resolve(name)

    private fun partFile(target: Path): Path = target.resolveSibling(target.fileName.toString() + ".part")

    private fun server(
        handler: (request: RawHttpServer.Request, requestCount: Int) -> RawHttpServer.Response,
    ): RawHttpServer {
        val rawServer = RawHttpServer(handler)
        rawServer.start()
        servers.add(rawServer)
        return rawServer
    }
}
