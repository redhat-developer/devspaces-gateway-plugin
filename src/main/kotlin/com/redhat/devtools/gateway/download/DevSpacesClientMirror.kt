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
import com.redhat.devtools.gateway.util.fragmentParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide activation of the local JetBrains Client mirror. While at least one
 * activation is open, [DevSpacesClientDownloaderConfigurationProvider] points the platform
 * thin-client downloader at the mirror via `file:` URLs, so that the client distribution is
 * copied from local disk instead of downloaded over the (possibly unstable) network.
 *
 * Activations nest and are tracked LIFO: [activeMirrorDir] is the most recently activated
 * still-open mirror dir, and closing an activation restores the dir that was active before
 * it. Closes are expected in reverse activation order — which `use`/`finally` naturally
 * produce for a single connect — but an out-of-order close removes only its own entry, so
 * a closed activation never leaves its mirror dir active for the still-open ones.
 */
object DevSpacesClientMirrorOverride {
    private val lock = Any()

    /** LIFO of the currently activated mirror dirs; the top is the active one. Guarded by [lock]. */
    private val activeMirrorDirs = mutableListOf<Path>()

    /**
     * The most recently activated still-open mirror dir, or null when no activation is open.
     * Volatile because readers (e.g. the downloader configuration provider) do not take [lock].
     */
    @Volatile
    var activeMirrorDir: Path? = null
        private set

    fun activate(mirrorDir: Path): AutoCloseable {
        synchronized(lock) {
            activeMirrorDirs.add(mirrorDir)
            activeMirrorDir = mirrorDir
        }
        return AutoCloseable {
            synchronized(lock) {
                // remove this activation's own entry: the top when closed in LIFO order,
                // otherwise its last occurrence (a slightly out-of-order close must not
                // leave the closed dir shadowing still-open activations)
                val lastOccurrence = activeMirrorDirs.lastIndexOf(mirrorDir)
                if (lastOccurrence >= 0) {
                    activeMirrorDirs.removeAt(lastOccurrence)
                }
                activeMirrorDir = activeMirrorDirs.lastOrNull()
            }
        }
    }

    @TestOnly
    fun resetForTest() {
        synchronized(lock) {
            activeMirrorDirs.clear()
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
 * Each mirrored archive is accompanied by its *sidecars*: the `.sha256` checksum of the
 * archive and, when upstream publishes it, the `.sha256.asc` detached signature of that
 * checksum file (not of the archive itself).
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

        fun defaultMirrorDir(): Path = Path.of(PathManager.getDefaultSystemPathFor(MIRROR_DIR_NAME))

        /**
         * Process-wide mutexes that serialize the mirroring of each target file. Keyed by
         * normalized target path: a [DevSpacesClientMirror] instance is created per connect,
         * but the mirror dir — and with it the target files — is shared by all connects.
         */
        private val mirrorLocks = ConcurrentHashMap<Path, Mutex>()

        /** The mutex that serializes all mirror downloads of [targetFile]. */
        private fun lockFor(targetFile: Path): Mutex =
            mirrorLocks.computeIfAbsent(targetFile.toAbsolutePath().normalize()) { Mutex() }

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

    private val checksumSidecars = ChecksumSidecars(downloader)

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

    /**
     * Downloads the artifact at [url] into the mirror, together with its sidecars.
     *
     * Mirroring of the same target file is serialized process-wide: a background workspace
     * restart (`DevWorkspaceRestart`) reconnects through here without being blocked by a
     * connect's modal progress dialog, and two downloads appending to the same `.part` file
     * would corrupt each other. A caller that waits for the lock re-runs the
     * [isFullyMirrored] check once it holds it and skips the download when the archive and
     * sidecars are ready.
     *
     * Requires a parseable `.sha256` checksum to be published next to [url] and throws
     * [IOException] otherwise, without downloading the archive: the platform verifies the
     * mirrored archive against that checksum and [isFullyMirrored] declares an archive
     * without it incomplete, so a mirror published without a checksum would be re-downloaded
     * on every connect. The `.sha256.asc` signature sidecar stays optional; see
     * [isFullyMirrored].
     */
    private suspend fun downloadToMirror(
        url: String,
        caption: String,
        progress: DevSpacesClientConnectProgress,
        checkCancelled: (() -> Unit)?
    ) {
        val uri = URI(url)
        val fileName = requireFileName(uri, url)
        val targetFile = mirrorDir.resolve(fileName)

        lockFor(targetFile).withLock {
            if (isFullyMirrored(targetFile, caption)) {
                return@withLock
            }
            // Incomplete mirror (archive present, sidecars missing, CRW-12799): discard the orphan
            // archive and its .part. Without its checksum sidecar the archive is incomplete and must
            // be re-fetched from scratch — a remirror cannot resume into a finalized orphan archive —
            // and keeping it would double the disk usage until the remirror replaces it.
            if (Files.exists(targetFile)) {
                Files.deleteIfExists(targetFile)
                Files.deleteIfExists(ResumableFileDownloader.partFileFor(targetFile))
            }

            progress.downloading(caption, fileName)

            checksumSidecars.downloadStaged(url, targetFile, checkCancelled).use { sidecars ->
                val expectedChecksum = sidecars.expectedChecksum
                    ?: throw IOException("No usable checksum published for $url; refusing to mirror the archive")
                downloader.download(
                    uri,
                    targetFile,
                    expectedChecksum = expectedChecksum,
                    onProgress = { downloadProgress ->
                        progress.downloading(caption, downloadProgress)
                    },
                    checkCancelled = checkCancelled,
                )
                sidecars.publish()
            }
        }
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
     * Returns `true` when the mirror already holds a complete copy of [targetFile] — the archive
     * plus the `.sha256` checksum sidecar — so [downloadToMirror] can skip the download.
     *
     * Presence is a sufficient completeness signal and no archive hash is needed here:
     * [ResumableFileDownloader] publishes the archive only after verifying it against the
     * checksum, and [ChecksumSidecars] publishes the sidecars only after that succeeded. An
     * archive can therefore never be seen next to a sidecar from an incomplete download.
     *
     * The `.sha256` checksum sidecar is required (unlike the `.asc` below): [downloadToMirror]
     * mirrors nothing without one, so an archive without it can only be the remains of an
     * interrupted mirror — the archive was published but the sidecars were not yet — and
     * must be re-mirrored to recover.
     *
     * The `.sha256.asc` signature sidecar (the detached signature of the checksum file) is
     * deliberately not required: not every upstream artifact publishes it, and
     * [ChecksumSidecars] therefore tolerates its absence, just like the platform does when
     * signature verification is off. Requiring it here would declare such mirrors incomplete
     * forever and re-download the archive on every connect although the previous connect
     * already succeeded without the sidecar.
     */
    private fun isFullyMirrored(targetFile: Path, caption: String): Boolean {
        if (!Files.exists(targetFile)) {
            return false
        }
        if (checksumSidecars.hasChecksum(targetFile)) {
            thisLogger().info("$caption already mirrored at $targetFile")
            return true
        }
        thisLogger().info("$caption at $targetFile is missing its checksum sidecar; mirroring again")
        return false
    }

    private fun noopActivation(): AutoCloseable = AutoCloseable { }

}
