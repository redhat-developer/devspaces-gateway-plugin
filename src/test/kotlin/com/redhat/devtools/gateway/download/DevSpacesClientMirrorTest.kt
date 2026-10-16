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
import com.redhat.devtools.gateway.util.ProgressCountdown
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class DevSpacesClientMirrorTest {

    @JvmField
    @TempDir
    var tempDir: Path? = null

    private val downloadedUrls = mutableListOf<URI>()

    @AfterEach
    fun afterEach() {
        DevSpacesClientMirrorOverride.resetForTest()
    }

    @Test
    fun `mirrors client, checksums and jre and activates the override`() = runTest {
        // given
        val sessionInfo = sessionInfo(
            clientUrl = "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
            jreUrl = "https://download.jetbrains.com/idea/jbr/jbr_jcef-25_0_2-osx-aarch64-b329.72.tar.gz",
        )
        val mirror = mirror(isClientDownloaded = { false }, sessionInfo = sessionInfo)

        // when
        val activation = mirror.ensureMirrored(joinLink(cb = "261.222.1", jb = "25.0.2b329.72"))

        // then
        assertThat(downloadedUrls.map { it.toString() }).containsExactlyInAnyOrder(
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit.sha256",
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit.sha256.asc",
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
            "https://download.jetbrains.com/idea/jbr/jbr_jcef-25_0_2-osx-aarch64-b329.72.tar.gz.sha256",
            "https://download.jetbrains.com/idea/jbr/jbr_jcef-25_0_2-osx-aarch64-b329.72.tar.gz.sha256.asc",
            "https://download.jetbrains.com/idea/jbr/jbr_jcef-25_0_2-osx-aarch64-b329.72.tar.gz",
        )
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isEqualTo(tempDir)
        assertThat(activation).isNotNull

        activation.close()
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isNull()
    }

    @Test
    fun `does not re-download an already mirrored client`() = runTest {
        // given
        val sessionInfo = sessionInfo(
            clientUrl = "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
        )
        val mirror = mirror(isClientDownloaded = { false }, sessionInfo = sessionInfo)
        placeMirroredArchive(".sha256", ".sha256.asc")

        // when
        mirror.ensureMirrored(joinLink(cb = "261.222.1", jb = "25.0.2b329.72"))

        // then
        assertThat(downloadedUrls).isEmpty()
    }

    @Test
    fun `re-mirrors a client whose sha256 sidecar is missing`() = runTest {
        // given
        val sessionInfo = sessionInfo(
            clientUrl = "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
        )
        val mirror = mirror(isClientDownloaded = { false }, sessionInfo = sessionInfo)
        placeMirroredArchive(".sha256.asc") // .sha256 missing

        // when
        val activation = mirror.ensureMirrored(joinLink(cb = "261.222.1", jb = "25.0.2b329.72"))

        // then
        assertThat(downloadedUrls.map { it.toString() }).containsExactlyInAnyOrder(
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit.sha256",
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit.sha256.asc",
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
        )
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isEqualTo(tempDir)
        activation.close()
    }

    @Test
    fun `does not re-download a client whose sha256 asc sidecar is missing`() = runTest {
        // given
        val sessionInfo = sessionInfo(
            clientUrl = "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
        )
        val mirror = mirror(isClientDownloaded = { false }, sessionInfo = sessionInfo)
        placeMirroredArchive(".sha256") // .sha256.asc missing

        // when
        val activation = mirror.ensureMirrored(joinLink(cb = "261.222.1", jb = "25.0.2b329.72"))

        // then
        assertThat(downloadedUrls).isEmpty()
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isEqualTo(tempDir)
        activation.close()
    }

    @Test
    fun `does not re-download on the next connect when upstream publishes no sha256 asc sidecar`() = runTest {
        // given
        val sessionInfo = sessionInfo(
            clientUrl = "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
        )
        val mirror = mirror(
            isClientDownloaded = { false },
            sessionInfo = sessionInfo,
            // upstream has no .sha256.asc: a 404 is permanent but non-fatal
            permanentFailureFor = { url -> url.path.endsWith(".sha256.asc") },
        )

        // when: first connect — everything upstream is mirrored, the missing .asc is tolerated
        val firstConnect = mirror.ensureMirrored(joinLink(cb = "261.222.1", jb = "25.0.2b329.72"))

        // then: the client and its .sha256 were mirrored, the missing .asc is tolerated
        assertThat(downloadedUrls.map { it.toString() }).containsExactlyInAnyOrder(
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit.sha256",
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
        )
        firstConnect.close()

        // when: second connect — the mirror without the .asc is complete, nothing is downloaded again
        downloadedUrls.clear()
        val secondConnect = mirror.ensureMirrored(joinLink(cb = "261.222.1", jb = "25.0.2b329.72"))

        // then
        assertThat(downloadedUrls).isEmpty()
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isEqualTo(tempDir)
        secondConnect.close()
    }

    @Test
    fun `discards a stale part file when re-mirroring an incomplete client`() = runTest {
        // given
        val sessionInfo = sessionInfo(
            clientUrl = "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
        )
        val mirror = mirror(isClientDownloaded = { false }, sessionInfo = sessionInfo)
        placeMirroredArchive() // no sidecars at all
        Files.writeString(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.part"), "partial")

        // when
        mirror.ensureMirrored(joinLink(cb = "261.222.1", jb = "25.0.2b329.72"))

        // then
        assertThat(downloadedUrls.map { it.toString() }).contains(
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit"
        )
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.part")).doesNotExist()
    }

    @Test
    fun `skips mirroring when the platform cache already holds the client`() = runTest {
        // given
        val mirror = mirror(isClientDownloaded = { true })

        // when
        val activation = mirror.ensureMirrored(joinLink(cb = "261.222.1", jb = "25.0.2b329.72"))

        // then
        assertThat(downloadedUrls).isEmpty()
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isNull()
        activation.close()
    }

    @Test
    fun `skips mirroring for snapshot builds`() = runTest {
        // given
        val mirror = mirror(isClientDownloaded = { false })

        // when
        val activation = mirror.ensureMirrored(joinLink(cb = "261.SNAPSHOT"))

        // then
        assertThat(downloadedUrls).isEmpty()
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isNull()
        activation.close()
    }

    @Test
    fun `skips mirroring when the join link has no client build`() = runTest {
        // given
        val mirror = mirror(isClientDownloaded = { false })

        // when
        val activation = mirror.ensureMirrored(URI("tcp://127.0.0.1:5990#jt=token&p=IU"))

        // then
        assertThat(downloadedUrls).isEmpty()
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isNull()
        activation.close()
    }

    @Test
    fun `fails on an invalid client build in the join link`() = runTest {
        // given
        val mirror = mirror(isClientDownloaded = { false })

        // when
        assertThrows<IOException> {
            mirror.ensureMirrored(joinLink(cb = "not-a-build-number"))
        }

        // then
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isNull()
    }

    @Test
    fun `reports download progress`() = runTest {
        // given
        val sessionInfo = sessionInfo(
            clientUrl = "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
        )
        val mirror = mirror(isClientDownloaded = { false }, sessionInfo = sessionInfo)
        val progress = mutableListOf<ProgressCountdown.ProgressEvent>()

        // when
        mirror.ensureMirrored(
            joinLink(cb = "261.222.1", jb = "25.0.2b329.72"),
            DevSpacesClientConnectProgress { progress.add(it) },
        )

        // then
        assertThat(progress).isNotEmpty
        assertThat(progress.first().title).contains("Downloading JetBrains Client:")
        assertThat(progress.first().message).contains("JetBrainsClient-")
        assertThat(progress.last().message).contains(" of ").contains("%")
    }

    // ---------------------------------------------------------------------------------------------

    /** Places a pre-mirrored client archive with the given sidecar suffixes (e.g. ".sha256"). */
    private fun placeMirroredArchive(vararg sidecarSuffixes: String) {
        val archiveName = "JetBrainsClient-261.222.1-aarch64.sit"
        Files.writeString(tempDir!!.resolve(archiveName), "already here")
        sidecarSuffixes.forEach {
            Files.writeString(tempDir!!.resolve(archiveName + it), "sidecar")
        }
    }

    private fun joinLink(cb: String?, jb: String? = null): URI {
        val params = buildList {
            add("jt=some-token")
            cb?.let { add("cb=$it") }
            jb?.let { add("jb=$it") }
        }
        return URI("tcp://127.0.0.1:5990#${params.joinToString("&")}")
    }

    private fun mirror(
        isClientDownloaded: (BuildNumber) -> Boolean,
        sessionInfo: JetBrainsClientDownloadInfo? = null,
        permanentFailureFor: (URI) -> Boolean = { false },
    ): DevSpacesClientMirror {
        val downloader = mockk<ResumableFileDownloader>(relaxed = true)
        coEvery { downloader.download(any(), any(), any(), any(), any()) } answers {
            val url = firstArg<URI>()
            if (permanentFailureFor(url)) {
                throw PermanentDownloadException("HTTP 404 for '$url'")
            }
            downloadedUrls.add(url)
            // materialize the target file so that the mirror sees it as downloaded
            val targetFile = secondArg<Path>()
            Files.createDirectories(targetFile.parent)
            Files.writeString(targetFile, "content of ${url.path.substringAfterLast('/')}")
            val onProgress: ((ResumableFileDownloader.Progress) -> Unit)? = arg(3)
            onProgress?.invoke(ResumableFileDownloader.Progress(1024, 4096))
            Unit
        }
        return DevSpacesClientMirror(
            downloader = downloader,
            mirrorDir = tempDir!!,
            isClientDownloaded = isClientDownloaded,
            createSessionInfo = { _, _ ->
                sessionInfo ?: JetBrainsClientDownloadInfo(
                    buildNumber("261.222.1"),
                    buildNumber("261.222.1"),
                    "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
                    null,
                    null,
                )
            },
        )
    }

    private fun sessionInfo(clientUrl: String, jreUrl: String? = null): JetBrainsClientDownloadInfo =
        JetBrainsClientDownloadInfo(
            buildNumber("261.222.1"),
            buildNumber("261.222.1"),
            clientUrl,
            jreUrl,
            null,
        )

    private fun buildNumber(value: String): BuildNumber = BuildNumber.fromStringOrNull(value)!!
}
