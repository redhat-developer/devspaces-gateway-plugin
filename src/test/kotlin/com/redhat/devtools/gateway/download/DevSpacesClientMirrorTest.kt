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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import java.util.concurrent.atomic.AtomicInteger

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
    fun `nested activations restore the previous mirror dir on close`() {
        // given: two distinct mirror dirs
        val firstMirrorDir = tempDir!!.resolve("mirror-1")
        val secondMirrorDir = tempDir!!.resolve("mirror-2")

        // when: the first mirror is activated, then the second on top of it
        val firstActivation = DevSpacesClientMirrorOverride.activate(firstMirrorDir)
        val secondActivation = DevSpacesClientMirrorOverride.activate(secondMirrorDir)

        // then: the most recently activated mirror is the active one
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isEqualTo(secondMirrorDir)

        // when: the inner activation closes
        secondActivation.close()

        // then: the previously activated mirror is active again
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isEqualTo(firstMirrorDir)

        // when: the outer activation closes
        firstActivation.close()

        // then: no mirror is active anymore
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isNull()
    }

    @Test
    fun `closing an activation out of order keeps the still-open mirror active`() {
        // given: two nested activations of distinct mirror dirs
        val firstMirrorDir = tempDir!!.resolve("mirror-1")
        val secondMirrorDir = tempDir!!.resolve("mirror-2")
        val firstActivation = DevSpacesClientMirrorOverride.activate(firstMirrorDir)
        val secondActivation = DevSpacesClientMirrorOverride.activate(secondMirrorDir)

        // when: the outer activation closes although the inner one is still open
        firstActivation.close()

        // then: the still-open inner activation stays active; the closed dir does not shadow it
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isEqualTo(secondMirrorDir)

        // when: the remaining activation closes
        secondActivation.close()

        // then: no mirror is active anymore
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
    fun `a connect waiting for an in-flight download of the same client skips re-downloading it`() = runTest {
        // given: the client archive download is held in flight once it started
        val archiveDownload = DownloadGate()
        val clientCacheChecks = AtomicInteger(0)
        val secondConnectMirroring = CompletableDeferred<Unit>()
        val sessionInfo = sessionInfo(
            clientUrl = "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
        )
        val mirror = mirror(
            isClientDownloaded = {
                // the second connect signals here: from the cache check it reaches the
                // per-target mirror lock without suspending again
                if (clientCacheChecks.incrementAndGet() == 2) secondConnectMirroring.complete(Unit)
                false
            },
            sessionInfo = sessionInfo,
            gateFor = { url -> if (url.path.endsWith(".sit")) archiveDownload else null },
        )

        // when: a first connect downloads the client and is held mid-archive ...
        val firstConnect = async { mirror.ensureMirrored(joinLink(cb = "261.222.1")) }
        archiveDownload.inFlight.await()
        // ... and a second connect for the same client starts mirroring while that download
        // is in flight (a background workspace restart is not blocked by a modal connect)
        val secondConnect = async { mirror.ensureMirrored(joinLink(cb = "261.222.1")) }
        secondConnectMirroring.await()
        archiveDownload.release.complete(Unit)

        // then: both connects succeed ...
        firstConnect.await().close()
        secondConnect.await().close()

        // ... but the archive was downloaded only once: the second connect waited for the
        // first to publish the archive and its sidecars and then found the mirror complete
        assertThat(downloadedUrls.map { it.toString() }).containsOnlyOnce(
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit"
        )
    }

    @Test
    fun `fails without downloading the archive when upstream publishes no sha256 checksum`() = runTest {
        // given: upstream publishes no .sha256 for the client archive
        val sessionInfo = sessionInfo(
            clientUrl = "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
        )
        val mirror = mirror(
            isClientDownloaded = { false },
            sessionInfo = sessionInfo,
            // upstream has no .sha256: a 404 is permanent, and without a checksum there is
            // nothing to verify the mirror against
            permanentFailureFor = { url -> url.path.endsWith(".sha256") },
        )

        // when
        val error = assertThrows<IOException> {
            mirror.ensureMirrored(joinLink(cb = "261.222.1", jb = "25.0.2b329.72"))
        }

        // then: mirroring fails, naming the artifact ...
        assertThat(error.message)
            .contains("checksum")
            .contains("https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit")
        // ... the archive was not downloaded ...
        assertThat(downloadedUrls.map { it.toString() }).doesNotContain(
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit"
        )
        // ... nothing was mirrored or left staged, and the override stays inactive
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit")).doesNotExist()
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.sha256")).doesNotExist()
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.sha256.asc")).doesNotExist()
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.sha256.staged")).doesNotExist()
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.sha256.asc.staged")).doesNotExist()
        assertThat(DevSpacesClientMirrorOverride.activeMirrorDir).isNull()
    }

    @Test
    fun `discards the orphan archive and stale part file when re-mirroring an incomplete client`() = runTest {
        // given: an incomplete mirror — an orphan archive without sidecars and a stale .part
        val sessionInfo = sessionInfo(
            clientUrl = "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
        )
        var failArchive = true
        val mirror = mirror(
            isClientDownloaded = { false },
            sessionInfo = sessionInfo,
            // the archive download fails; whatever the remirror discarded beforehand stays gone
            failureFor = { url -> failArchive && url.path.endsWith(".sit") },
        )
        placeMirroredArchive() // no sidecars at all
        Files.writeString(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.part"), "partial")

        // when: the remirror starts but the archive download fails
        assertThrows<IOException> {
            mirror.ensureMirrored(joinLink(cb = "261.222.1", jb = "25.0.2b329.72"))
        }

        // then: the remirror discarded the orphan archive and the stale .part before downloading;
        // the failed download wrote nothing, so their absence proves the cleanup
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit")).doesNotExist()
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.part")).doesNotExist()

        // when: the next connect retries and the archive download succeeds
        downloadedUrls.clear()
        failArchive = false
        val activation = mirror.ensureMirrored(joinLink(cb = "261.222.1", jb = "25.0.2b329.72"))

        // then: the archive was re-mirrored from scratch
        assertThat(downloadedUrls.map { it.toString() }).contains(
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit"
        )
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit")).exists()
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.part")).doesNotExist()
        activation.close()
    }

    @Test
    fun `keeps sidecars staged when the archive download fails`() = runTest {
        // given: a stale archive without sidecars and a failing archive download
        val sessionInfo = sessionInfo(
            clientUrl = "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
        )
        var failArchive = true
        val mirror = mirror(
            isClientDownloaded = { false },
            sessionInfo = sessionInfo,
            failureFor = { url -> failArchive && url.path.endsWith(".sit") },
        )
        placeMirroredArchive() // stale archive, no sidecars

        // when
        assertThrows<IOException> {
            mirror.ensureMirrored(joinLink(cb = "261.222.1", jb = "25.0.2b329.72"))
        }

        // then: the sidecars were not published next to the stale archive
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.sha256")).doesNotExist()
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.sha256.asc")).doesNotExist()
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.sha256.staged")).doesNotExist()
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.sha256.asc.staged")).doesNotExist()

        // when: the next connect retries and the archive download succeeds
        downloadedUrls.clear()
        failArchive = false
        val activation = mirror.ensureMirrored(joinLink(cb = "261.222.1", jb = "25.0.2b329.72"))

        // then
        assertThat(downloadedUrls.map { it.toString() }).containsExactlyInAnyOrder(
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit.sha256",
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit.sha256.asc",
            "https://download.jetbrains.com/idea/code-with-me/JetBrainsClient-261.222.1-aarch64.sit",
        )
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.sha256")).exists()
        assertThat(tempDir!!.resolve("JetBrainsClient-261.222.1-aarch64.sit.sha256.asc")).exists()
        activation.close()
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
        failureFor: (URI) -> Boolean = { false },
        gateFor: (URI) -> DownloadGate? = { null },
    ): DevSpacesClientMirror {
        val downloader = mockk<ResumableFileDownloader>(relaxed = true)
        coEvery { downloader.download(any(), any(), any(), any(), any()) } coAnswers {
            val url = firstArg<URI>()
            if (permanentFailureFor(url)) {
                throw PermanentDownloadException("HTTP 404 for '$url'")
            }
            if (failureFor(url)) {
                throw IOException("Connection closed while downloading '$url'")
            }
            gateFor(url)?.let { gate ->
                // hold the download in flight until the test releases it
                gate.inFlight.complete(Unit)
                gate.release.await()
            }
            downloadedUrls.add(url)
            // materialize the target file so that the mirror sees it as downloaded
            val targetFile = secondArg<Path>()
            Files.createDirectories(targetFile.parent)
            val content = if (url.path.endsWith(".sha256")) {
                // a parseable checksum so that the mirror accepts it as usable
                "$SHA256_SIDE_CAR_CONTENT  ${url.path.substringAfterLast('/')}\n"
            } else {
                "content of ${url.path.substringAfterLast('/')}"
            }
            Files.writeString(targetFile, content)
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

    /**
     * Holds a fake download in flight: [inFlight] completes once the download started,
     * [release] lets it finish. Lets a test start other work while a download is running.
     */
    private class DownloadGate {
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    companion object {
        /**
         * Content the test downloader serves for `.sha256` sidecars; parseable so that the
         * mirror accepts it as a usable checksum.
         */
        private const val SHA256_SIDE_CAR_CONTENT =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
