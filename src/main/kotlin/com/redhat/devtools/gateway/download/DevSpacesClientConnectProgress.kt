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

import com.redhat.devtools.gateway.util.ProgressCountdown
import java.util.Locale

/**
 * The single producer of the user-facing progress texts of a thin-client connect.
 *
 * Both the connect flow ([com.redhat.devtools.gateway.DevSpacesConnection]) and the
 * first-time client download ([DevSpacesClientMirror]) report through one instance, so that the
 * wizard dialog shows one coherent story instead of unrelated per-phase messages:
 *
 * 1. waiting for the client to start (the platform may download it — may take minutes),
 * 2. downloading the client with resume support (first-time connect, granular progress),
 * 3. starting the client.
 *
 * Kept in one place so phrasing, captions and byte formatting stay consistent.
 */
class DevSpacesClientConnectProgress(private val onProgress: ((ProgressCountdown.ProgressEvent) -> Unit)? = null) {

    companion object {
        const val JB_CLIENT_CAPTION = "JetBrains Client"
        const val JB_RUNTIME_CAPTION = "JetBrains Runtime"

        private const val WAITING_FOR_CLIENT_START =
            "Waiting for the workspace IDE client to start (first-time download may take several minutes)..."
        private const val STARTING_CLIENT = "Starting the workspace IDE client..."

        private const val BYTES_PER_KB = 1024.0
        private const val BYTES_PER_MB = BYTES_PER_KB * BYTES_PER_KB
        private const val PERCENT = 100L

        private fun formatBytes(bytes: Long): String {
            val megaBytes = bytes / BYTES_PER_MB
            return if (megaBytes >= 1.0) {
                String.format(Locale.ROOT, "%.1f MB", megaBytes)
            } else {
                String.format(Locale.ROOT, "%.0f KB", bytes / BYTES_PER_KB)
            }
        }
    }

    /** Emitted while the connect prepares port forwarding and the client mirror. */
    fun waitingForClientStart() {
        report(WAITING_FOR_CLIENT_START, "")
    }

    /** Emitted when the download of a client distribution starts. */
    fun downloading(caption: String, fileName: String) {
        report("Downloading $caption:", "$fileName...")
    }

    /**
     * Emitted while a client distribution downloads; throttled by the downloader.
     *
     * The static caption is reported as the title (large text above the progress bar,
     * matching the other connect phases) while the dynamic byte counts stay in the
     * small text below the bar.
     */
    fun downloading(caption: String, progress: ResumableFileDownloader.Progress) {
        val message = if (progress.totalBytes != null && progress.totalBytes > 0) {
            val percent = progress.downloadedBytes * PERCENT / progress.totalBytes
            "${formatBytes(progress.downloadedBytes)} of ${formatBytes(progress.totalBytes)} ($percent%)"
        } else {
            formatBytes(progress.downloadedBytes)
        }
        report("Downloading $caption:", message)
    }

    /** Emitted once the client distribution is available and the client is being started. */
    fun startingClient() {
        // an empty message clears the details line, so the last download figures
        // (e.g. "673.6 MB of 673.6 MB (100%)") do not linger below the bar
        report(STARTING_CLIENT, "")
    }

    private fun report(title: String, message: String?) {
        onProgress?.invoke(ProgressCountdown.ProgressEvent(title = title, message = message))
    }
}
