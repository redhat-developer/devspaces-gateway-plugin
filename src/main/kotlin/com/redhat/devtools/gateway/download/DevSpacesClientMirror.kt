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

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.BuildNumber
import com.intellij.remoteDev.connection.JetBrainsClientDownloadInfo
import com.intellij.remoteDev.downloader.CodeWithMeClientDownloader
import com.redhat.devtools.gateway.util.Sha256Checksum
import com.redhat.devtools.gateway.util.fragmentParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Refcounted activation of the local JetBrains Client mirror. While active,
 * [DevSpacesClientDownloaderConfigurationProvider] points the platform thin-client
 * downloader at the mirror via `file:` URLs, so that the client distribution is copied
 * from local disk instead of downloaded over the (possibly unstable) network.
 */
object DevSpacesClientMirrorOverride {
    private val lock = Any()
    private val depth = AtomicInteger(0)

    @Volatile
    var activeMirrorDir: Path? = null
        private set

    fun activate(mirrorDir: Path): AutoCloseable {
        synchronized(lock) {
            depth.incrementAndGet()
            activeMirrorDir = mirrorDir
        }
        return AutoCloseable {
            synchronized(lock) {
                if (depth.decrementAndGet() <= 0) {
                    depth.set(0)
                    activeMirrorDir = null
                }
            }
        }
    }

    @TestOnly
    fun resetForTest() {
        synchronized(lock) {
            depth.set(0)
            activeMirrorDir = null
        }
    }
}

/**
 * Mirrors the JetBrains Client (and, when required, its JBR) for a workspace join link
 * into a local directory, using [ResumableFileDownloader].
 *
 * The platform's own downloader (`CodeWithMeClientDownloader`) has no resume support: on
 * links where transfers get cut after a few seconds (VPN, corporate proxies) its 5
 * attempts each restart from byte 0 and a first-time 700+ MB client download always
 * fails, which then surfaces as "workspace IDE is not ready". The browser opens the very
 * same URL fine because it silently resumes interrupted downloads.
 *
 * After [ensureMirrored] populated the mirror, [DevSpacesClientMirrorOverride] is activated for
 * the duration of the connect, and the platform reads the client distribution from the
 * mirror. The platform still verifies checksums and signatures and performs the
 * extraction itself, exactly as for a network download.
 *
 * @param downloader used for all mirror downloads; injectable for testing
 * @param mirrorDir directory the client archives are mirrored into
 * @param isClientDownloaded returns true when the platform client cache already holds an
 *   extracted client for the given build (no mirroring needed then); asks the platform
 *   itself whether the client was already downloaded in a previous attempt
 * @param createSessionInfo builds the download info for a client build exactly like the
 *   platform does before launching a thin client
 */
@Suppress("UnstableApiUsage")
class DevSpacesClientMirror(
    private val downloader: ResumableFileDownloader = ResumableFileDownloader(),
    private val mirrorDir: Path = defaultMirrorDir(),
    private val isClientDownloaded: (BuildNumber) -> Boolean = ::isClientDownloaded,
    private val createSessionInfo: (clientBuild: BuildNumber, jreBuild: String?) -> JetBrainsClientDownloadInfo =
        ::createPlatformSessionInfo,
) {

    companion object {
        private const val MIRROR_DIR_NAME = "DevSpacesClientMirror"

        private const val CLIENT_BUILD_PARAM = "cb"
        private const val JRE_BUILD_PARAM = "jb"
        private const val SHA256_SUFFIX = ".sha256"
        private const val SHA256_ASC_SUFFIX = ".sha256.asc"

        /** Same part-file suffix [ResumableFileDownloader] appends while downloading. */
        private const val PART_SUFFIX = ".part"

        fun defaultMirrorDir(): Path = Path.of(PathManager.getDefaultSystemPathFor(MIRROR_DIR_NAME))

        /**
         * Asks the platform whether the client for [build] was already downloaded (in a
         * previous attempt) and is present in its cache. Delegates to
         * [CodeWithMeClientDownloader.isClientDownloaded]; returns false when the check
         * fails so that a mirrored download is attempted rather than silently skipping one.
         *
         * @param build the build number of the client to check
         * @return true if the client was already downloaded, false otherwise
         */
        private fun isClientDownloaded(build: BuildNumber): Boolean =
            runCatching { CodeWithMeClientDownloader.isClientDownloaded(build) }
                .onFailure { thisLogger().debug("Could not check the platform client cache for $build", it) }
                .getOrDefault(false)

        private fun createPlatformSessionInfo(
            clientBuild: BuildNumber,
            jreBuild: String?
        ): JetBrainsClientDownloadInfo =
            CodeWithMeClientDownloader.createSessionInfo(clientBuild, jreBuild, true)
    }

    /**
     * Ensures the client referenced by [joinLink] is mirrored, and activates
     * [DevSpacesClientMirrorOverride]. Returns the activation to close once the connect ended
     * (successfully or not).
     *
     * Does nothing (returns an inactive handle) when the join link carries no client
     * build, refers to a snapshot build, or when the platform client cache already holds
     * an extracted client for the build.
     */
    suspend fun ensureMirrored(
        joinLink: URI,
        progress: DevSpacesClientConnectProgress = DevSpacesClientConnectProgress(),
        checkCancelled: (() -> Unit)? = null
    ): AutoCloseable = withContext(Dispatchers.IO) {
        val params = joinLink.fragmentParameters()
        val clientBuildParam = params[CLIENT_BUILD_PARAM]
        if (clientBuildParam == null) {
            thisLogger().info("Join link carries no client build; skipping client mirroring")
            return@withContext noopActivation()
        }
        val clientBuild = BuildNumber.fromStringOrNull(clientBuildParam)
            ?: throw IOException("Invalid client build number '$clientBuildParam' in join link")
        if (clientBuild.isSnapshot) {
            // snapshot builds resolve the concrete build dynamically; leave it to the platform
            thisLogger().info("Snapshot client build $clientBuild; skipping client mirroring")
            return@withContext noopActivation()
        }
        if (isClientDownloaded(clientBuild)) {
            thisLogger().info("JetBrains Client $clientBuild already extracted in the IDE cache")
            return@withContext noopActivation()
        }

        val sessionInfo = createSessionInfo(clientBuild, params[JRE_BUILD_PARAM])
        downloadToMirror(
            sessionInfo.compatibleClientUrl, DevSpacesClientConnectProgress.JB_CLIENT_CAPTION, progress, checkCancelled
        )
        sessionInfo.compatibleJreUrl?.let {
            downloadToMirror(it, DevSpacesClientConnectProgress.JB_RUNTIME_CAPTION, progress, checkCancelled)
        }

        thisLogger().info("JetBrains Client $clientBuild mirrored to $mirrorDir")
        DevSpacesClientMirrorOverride.activate(mirrorDir)
    }

    private suspend fun downloadToMirror(
        url: String,
        caption: String,
        progress: DevSpacesClientConnectProgress,
        checkCancelled: (() -> Unit)?
    ) {
        val uri = URI(url)
        val fileName = requireFileName(uri, url)
        val targetFile = mirrorDir.resolve(fileName)

        if (isFullyMirrored(fileName, caption)) {
            return
        }

        // Incomplete mirror (archive present, sidecars missing, CRW-12799): drop the stale .part
        if (Files.exists(targetFile)) {
            Files.deleteIfExists(mirrorDir.resolve(fileName + PART_SUFFIX))
        }

        progress.downloading(caption, fileName)

        // download the checksums first, archive can be verified against them
        val expectedChecksum = downloadChecksums(url, checkCancelled)

        downloader.download(
            uri,
            targetFile,
            expectedChecksum = expectedChecksum,
            onProgress = { downloadProgress ->
                progress.downloading(caption, downloadProgress)
            },
            checkCancelled = checkCancelled,
        )
    }

    /**
     * Returns the file name a download URL refers to, i.e. the last path segment.
     *
     * @throws IOException when the URL carries no usable file name
     */
    private fun requireFileName(uri: URI, url: String): String {
        val fileName = uri.path.substringAfterLast('/')
        if (fileName.isBlank()) {
            throw IOException("No file name in download URL: $url")
        }
        return fileName
    }

    /**
     * Returns `true` when the mirror already holds a complete copy of [fileName] — the archive
     * plus the `.sha256` checksum sidecar — so [downloadToMirror] can skip the download.
     *
     * The `.sha256.asc` signature sidecar is deliberately not required: not every upstream
     * artifact publishes it, and [downloadChecksumFile] therefore tolerates its absence, just
     * like the platform does when signature verification is off. Requiring it here would
     * declare such mirrors incomplete forever and re-download the archive on every connect
     * although the previous connect already succeeded without the sidecar.
     */
    private fun isFullyMirrored(fileName: String, caption: String): Boolean {
        val targetFile = mirrorDir.resolve(fileName)
        if (!Files.exists(targetFile)) {
            return false
        }
        if (Files.exists(mirrorDir.resolve(fileName + SHA256_SUFFIX))) {
            thisLogger().info("$caption already mirrored at $targetFile")
            return true
        }
        thisLogger().info("$caption at $targetFile is missing its checksum sidecar; mirroring again")
        return false
    }

    /**
     * Downloads both checksum sidecars into the mirror and returns the expected SHA-256 of the
     * archive, or `null` when none is published upstream (downloads without published checksums
     * still work, just unverified).
     */
    private suspend fun downloadChecksums(
        url: String,
        checkCancelled: (() -> Unit)?
    ): Sha256Checksum? {
        val checksumFile = downloadChecksumFile(url + SHA256_SUFFIX, checkCancelled)
        downloadChecksumFile(url + SHA256_ASC_SUFFIX, checkCancelled)
        return checksumFile?.let { Sha256Checksum.parse(Files.readString(it)) }
    }

    /**
     * Downloads a checksum file into the mirror; returns `null` (without failing) when
     * none is published upstream, so that downloads without published checksums still work.
     */
    private suspend fun downloadChecksumFile(
        checksumUrl: String,
        checkCancelled: (() -> Unit)?
    ): Path? {
        val fileName = URI(checksumUrl).path.substringAfterLast('/')
        val targetFile = mirrorDir.resolve(fileName)
        return try {
            downloader.download(URI(checksumUrl), targetFile, onProgress = null, checkCancelled = checkCancelled)
            targetFile
        } catch (e: PermanentDownloadException) {
            thisLogger().warn("No checksum at $checksumUrl (${e.message}); continuing without it")
            null
        }
    }

    private fun noopActivation(): AutoCloseable = AutoCloseable { }
}
