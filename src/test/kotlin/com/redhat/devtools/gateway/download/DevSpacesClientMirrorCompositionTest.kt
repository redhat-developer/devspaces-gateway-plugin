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

import com.intellij.openapi.util.BuildNumber
import com.intellij.remoteDev.connection.JetBrainsClientDownloadInfo
import com.redhat.devtools.gateway.util.ExponentialBackoff
import com.redhat.devtools.gateway.util.ProgressCountdown
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Composes everything a first-time thin-client download consists of — the resumable
 * downloader, the mirror, the download-URL override and the progress reporting — against
 * a server that is slow AND repeatedly cuts the connection mid-body (what the networks
 * look like on which the platform's non-resuming downloader always fails).
 *
 * It also plays the platform's part ([CodeWithMeClientDownloader]): while the mirror is
 * active, the download URL the override serves must let the platform copy the exact
 * archive and checksums via its native `file:` handling (`Paths.get` + `Files.copy`).
 */
class DevSpacesClientMirrorCompositionTest {

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
        DevSpacesClientMirrorOverride.resetForTest()
        watchdogScheduler.shutdownNow()
    }

    @Test
    fun `slow and repeatedly cut first-time download still completes and is served to the platform`() = runTest {
        // given
        val content = bytes(2_500_000)
        val checksum = sha256(content)
        val fileName = "JetBrainsClient-261.222.1-aarch64.sit"
        val archiveRequests = AtomicInteger()

        // every connection is slow (8 KB chunks) and cut after 300 KB, like a VPN
        // that severs streams after a few seconds
        val server = RawHttpServer { request, _ ->
            when {
                request.path.endsWith(".sha256.asc") -> RawHttpServer.Response(body = "signature".toByteArray())

                request.path.endsWith(".sha256") -> RawHttpServer.Response(body = "$checksum  $fileName\n".toByteArray())

                else -> {
                    archiveRequests.incrementAndGet()
                    val range = request.headers["range"]
                    val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                    val body = content.copyOfRange(start, content.size)
                    val cutAfter = if (body.size > CUT_AFTER_BYTES) CUT_AFTER_BYTES else -1
                    if (start == 0) {
                        RawHttpServer.Response(
                            body = body,
                            cutBodyAfter = cutAfter,
                            chunkSize = CHUNK_SIZE,
                            interChunkDelayMillis = 1,
                        )
                    } else {
                        RawHttpServer.Response(
                            status = "206 Partial Content",
                            headers = mapOf("Content-Range" to "bytes $start-${content.size - 1}/${content.size}"),
                            body = body,
                            cutBodyAfter = cutAfter,
                            chunkSize = CHUNK_SIZE,
                            interChunkDelayMillis = 1,
                        )
                    }
                }
            }
        }
        server.start()
        servers.add(server)

        val events = CopyOnWriteArrayList<ProgressCountdown.ProgressEvent>()
        val progress = DevSpacesClientConnectProgress { events.add(it) }
        val mirror = DevSpacesClientMirror(
            downloader = ResumableFileDownloader(
                httpClient = HttpClient.newHttpClient(),
                backoffFactory = { ExponentialBackoff(initialMillis = 1, maxMillis = 2) },
                watchdogSchedulerFactory = { watchdogScheduler },
            ),
            mirrorDir = tempDir!!,
            isClientDownloaded = { false },
            createSessionInfo = { _, _ ->
                JetBrainsClientDownloadInfo(
                    buildNumber("261.222.1"),
                    buildNumber("261.222.1"),
                    server.uri(fileName).toString(),
                    null,
                    null,
                )
            },
        )

        // when: the connect flow — wait, download (mirrored), start — one progress producer
        progress.waitingForClientStart()
        val activation = mirror.ensureMirrored(URI("tcp://127.0.0.1:5990#cb=261.222.1"), progress)
        activation.use {
            progress.startingClient()

            // then: what CodeWithMeClientDownloader does while the override is active:
            // createSessionInfo builds <clientDownloadUrl>/<fileName> and
            // downloadWithRetries copies it via Paths.get(file-URI)
            val provider = DevSpacesClientDownloaderConfigurationProvider()
            val clientUrl = URI(provider.clientDownloadUrl.toString().trimEnd('/') + "/" + fileName)
            assertThat(clientUrl.scheme).isEqualTo("file")
            val platformCopy = Files.createTempFile("platform-copy", ".sit")
            Files.copy(Path.of(clientUrl), platformCopy, StandardCopyOption.REPLACE_EXISTING)
            assertThat(platformCopy).hasBinaryContent(content)

            // the checksums the platform verifies the signature with are served, too
            val checksumUrl = URI(provider.clientDownloadUrl.toString().trimEnd('/') + "/" + fileName + ".sha256")
            val checksumCopy = Files.createTempFile("platform-copy", ".sha256")
            Files.copy(Path.of(checksumUrl), checksumCopy, StandardCopyOption.REPLACE_EXISTING)
            assertThat(checksumCopy).hasBinaryContent("$checksum  $fileName\n".toByteArray())
        }

        // then: the download survived repeated cuts by resuming
        assertThat(archiveRequests.get()).isGreaterThan(3)

        // then: one producer told the whole story — static captions as titles (large text
        // above the bar), dynamic download details as messages (small text below the bar)
        val titles = events.mapNotNull { it.title }
        val messages = events.mapNotNull { it.message }
        assertThat(titles.first()).contains("Waiting for the workspace IDE client")
        assertThat(titles).anyMatch { it.contains("Downloading JetBrains Client:") }
        assertThat(titles.last()).contains("Starting the workspace IDE client")
        assertThat(messages).anyMatch { it.contains(fileName) }.anyMatch { it.contains(" of ") && it.contains("%") }

        // then: after the connect ended, the override is inactive again
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isNull()
    }

    companion object {
        private const val CUT_AFTER_BYTES = 300 * 1024
        private const val CHUNK_SIZE = 8 * 1024

        private fun bytes(size: Int, seed: Int = 7): ByteArray = ByteArray(size) { ((it + seed) % 251).toByte() }

        private fun sha256(content: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(content)
                .joinToString("") { "%02x".format(it) }

        private fun buildNumber(value: String): BuildNumber = BuildNumber.fromStringOrNull(value)!!
    }
}
