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

import com.redhat.devtools.gateway.util.Sha256Checksum
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class ChecksumSidecarsTest {

    @JvmField
    @TempDir
    var tempDir: Path? = null

    private val checksumHex = "a".repeat(64)
    private val artifactUrl = "https://example.com/JetBrainsClient.sit"

    private val artifactFile: Path
        get() = tempDir!!.resolve("JetBrainsClient.sit")

    private val checksumFile: Path
        get() = tempDir!!.resolve("JetBrainsClient.sit.sha256")

    private val signatureFile: Path
        get() = tempDir!!.resolve("JetBrainsClient.sit.sha256.asc")

    @Test
    fun `stages sidecars and publishes them next to the artifact`() = runTest {
        // given
        val sidecars = ChecksumSidecars(downloader(::sidecarContent))

        // when
        sidecars.downloadStaged(artifactUrl, artifactFile).use { staged ->
            // then: the checksum is parsed and the sidecars stay staged until published
            assertThat(staged.expectedChecksum).isEqualTo(Sha256Checksum(checksumHex))
            assertThat(checksumFile).doesNotExist()
            assertThat(signatureFile).doesNotExist()
            staged.publish()
        }

        // then
        assertThat(checksumFile).exists()
        assertThat(signatureFile).exists()
        assertThat(stagedFile(checksumFile)).doesNotExist()
        assertThat(stagedFile(signatureFile)).doesNotExist()
    }

    @Test
    fun `continues without a signature sidecar that upstream does not publish`() = runTest {
        // given
        val sidecars = ChecksumSidecars(
            downloader { url ->
                if (url.path.endsWith(".sha256.asc")) {
                    throw PermanentDownloadException("HTTP 404 for '$url'")
                }
                checksumHex
            }
        )

        // when
        sidecars.downloadStaged(artifactUrl, artifactFile).use { it.publish() }

        // then
        assertThat(checksumFile).exists()
        assertThat(signatureFile).doesNotExist()
    }

    @Test
    fun `discards staged sidecars and their part files when not published`() = runTest {
        // given
        val sidecars = ChecksumSidecars(downloader(::sidecarContent))
        val staged = sidecars.downloadStaged(artifactUrl, artifactFile)
        val stagedChecksum = stagedFile(checksumFile)
        val stagedChecksumPart = stagedChecksum.resolveSibling(stagedChecksum.fileName.toString() + ".part")
        Files.writeString(stagedChecksumPart, "partial")

        // when
        staged.close()

        // then
        assertThat(stagedChecksum).doesNotExist()
        assertThat(stagedFile(signatureFile)).doesNotExist()
        assertThat(stagedChecksumPart).doesNotExist()
        assertThat(checksumFile).doesNotExist()
        assertThat(signatureFile).doesNotExist()
    }

    @Test
    fun `does not verify against an unparseable checksum sidecar`() = runTest {
        // given
        val sidecars = ChecksumSidecars(downloader { "not-a-sha256" })

        // when
        sidecars.downloadStaged(artifactUrl, artifactFile).use { staged ->
            // then
            assertThat(staged.expectedChecksum).isNull()
            staged.publish()
        }

        // then
        assertThat(checksumFile).exists()
    }

    @Test
    fun `hasChecksum reports only a published checksum sidecar`() {
        // given
        val sidecars = ChecksumSidecars(mockk(relaxed = true))

        // then
        assertThat(sidecars.hasChecksum(artifactFile)).isFalse

        Files.writeString(signatureFile, "signature")
        assertThat(sidecars.hasChecksum(artifactFile)).isFalse

        Files.writeString(checksumFile, checksumHex)
        assertThat(sidecars.hasChecksum(artifactFile)).isTrue
    }

    private fun downloader(contentFor: (URI) -> String): ResumableFileDownloader {
        val downloader = mockk<ResumableFileDownloader>(relaxed = true)
        coEvery { downloader.download(any(), any(), any(), any(), any()) } answers {
            val url = firstArg<URI>()
            val targetFile = secondArg<Path>()
            Files.writeString(targetFile, contentFor(url))
            Unit
        }
        return downloader
    }

    private fun sidecarContent(url: URI): String =
        if (url.path.endsWith(".sha256")) checksumHex else "signature"

    private fun stagedFile(targetFile: Path): Path =
        targetFile.resolveSibling(targetFile.fileName.toString() + ".staged")
}
