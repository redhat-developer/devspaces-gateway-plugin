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

import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.util.Sha256Checksum
import com.redhat.devtools.gateway.util.moveAtomically
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Downloads the *sidecars* of an artifact — the `.sha256` checksum of the artifact and, when
 * published, the `.sha256.asc` detached signature of that checksum file — and publishes them
 * only after the artifact download succeeded.
 *
 * Sidecars are downloaded under `.staged` names. [downloadStaged] returns them as a [Staged] handle:
 * [Staged.publish] moves them next to the artifact, [Staged.close] discards them (including
 * their `.part` files) when the artifact download failed. Keeping promotion and cleanup in a
 * single handle guarantees that a checksum sidecar is never published next to an incomplete
 * artifact.
 *
 * @param downloader used to download the sidecars; injectable for testing
 */
internal class ChecksumSidecars(
    private val downloader: ResumableFileDownloader = ResumableFileDownloader(),
) {

    companion object {
        private const val SHA256_SUFFIX = ".sha256"
        private const val SHA256_ASC_SUFFIX = ".sha256.asc"

        /**
         * Suffix of sidecars that are downloaded before, but only promoted to their final
         * names after, the artifact succeeded. A failed artifact download therefore never
         * leaves a checksum sidecar next to an incomplete artifact.
         */
        private const val STAGED_SUFFIX = ".staged"
    }

    /** Whether [targetFile] has a published `.sha256` checksum sidecar. */
    fun hasChecksum(targetFile: Path): Boolean =
        Files.exists(checksumFileFor(targetFile))

    /**
     * Downloads the sidecars of [artifactUrl] under staged names and returns them together
     * with the expected SHA-256 of the artifact, or `null` when no checksum is published
     * upstream (artifacts without published checksums still work, just unverified). The
     * returned sidecars must be [Staged.publish]ed after the artifact download succeeded.
     */
    suspend fun downloadStaged(
        artifactUrl: String,
        targetFile: Path,
        checkCancelled: (() -> Unit)? = null,
    ): Staged {
        val files = mutableListOf<StagedSidecar>()
        var staged = false
        try {
            val checksumSidecar = stageSidecar(
                URI(artifactUrl + SHA256_SUFFIX),
                checksumFileFor(targetFile),
                checkCancelled,
            )
            checksumSidecar?.let { files.add(it) }
            stageSidecar(
                URI(artifactUrl + SHA256_ASC_SUFFIX),
                signatureFileFor(targetFile),
                checkCancelled,
            )?.let { files.add(it) }
            val expectedChecksum = checksumSidecar?.let {
                Sha256Checksum.parse(Files.readString(it.stagedFile))
            }
            staged = true
            return Staged(expectedChecksum, files)
        } finally {
            if (!staged) {
                discard(files)
            }
        }
    }

    /**
     * Downloads a sidecar under a staged name; returns `null` (without failing) when none is
     * published upstream, so that artifacts without published checksums still work.
     */
    private suspend fun stageSidecar(
        sidecarUrl: URI,
        targetFile: Path,
        checkCancelled: (() -> Unit)?,
    ): StagedSidecar? {
        val stagedFile = targetFile.resolveSibling(targetFile.fileName.toString() + STAGED_SUFFIX)
        return try {
            downloader.download(sidecarUrl, stagedFile, onProgress = null, checkCancelled = checkCancelled)
            StagedSidecar(stagedFile, targetFile)
        } catch (e: PermanentDownloadException) {
            thisLogger().warn("No sidecar at $sidecarUrl (${e.message}); continuing without it")
            null
        }
    }

    /** Removes staged sidecars and their part files; best-effort so it never masks the original failure. */
    private fun discard(sidecars: List<StagedSidecar>) {
        sidecars.forEach { sidecar ->
            runCatching { Files.deleteIfExists(sidecar.stagedFile) }
            runCatching { Files.deleteIfExists(ResumableFileDownloader.partFileFor(sidecar.stagedFile)) }
        }
    }

    private fun checksumFileFor(targetFile: Path): Path =
        targetFile.resolveSibling(targetFile.fileName.toString() + SHA256_SUFFIX)

    private fun signatureFileFor(targetFile: Path): Path =
        targetFile.resolveSibling(targetFile.fileName.toString() + SHA256_ASC_SUFFIX)

    /** A sidecar downloaded to [stagedFile], waiting to be promoted to [targetFile]. */
    internal data class StagedSidecar(
        val stagedFile: Path,
        val targetFile: Path,
    )

    /**
     * Sidecars staged for one artifact download. They stay under their staged names until
     * [publish] moves them next to the artifact; [close] discards them when the artifact
     * download failed.
     */
    inner class Staged internal constructor(
        /** The SHA-256 the artifact must match, or `null` when upstream publishes no checksum. */
        val expectedChecksum: Sha256Checksum?,
        private val files: List<StagedSidecar>,
    ) : AutoCloseable {
        private var isPublished = false

        /** Publishes the staged sidecars next to the artifact. */
        fun publish() {
            files.forEach { moveAtomically(it.stagedFile, it.targetFile) }
            isPublished = true
        }

        /** Discards the staged sidecars (and their part files) unless they were [publish]ed. */
        override fun close() {
            if (!isPublished) {
                discard(files)
            }
        }
    }
}
