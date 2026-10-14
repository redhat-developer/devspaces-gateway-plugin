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
import com.intellij.util.concurrency.AppExecutorUtil
import com.redhat.devtools.gateway.auth.code.contentLength
import com.redhat.devtools.gateway.util.ExponentialBackoff
import com.redhat.devtools.gateway.util.IdeHttpProxy
import com.redhat.devtools.gateway.util.Sha256Checksum
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.util.moveAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val IO_BUFFER_SIZE = 64 * 1024

/** How often a blocked body read polls for cancellation. */
private const val CANCELLATION_POLL_INTERVAL_MILLIS = 500L

private const val HTTP_OK = 200
private const val HTTP_PARTIAL_CONTENT = 206
private const val HTTP_RANGE_NOT_SATISFIABLE = 416
private const val HTTP_REDIRECTED = 300
private const val HTTP_CLIENT_ERROR_MAX = 499
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429

/**
 * A download failure that retrying cannot fix (e.g. HTTP 4xx other than 416, 408 and 429),
 * so that callers can distinguish "gone for good" from "worth retrying".
 */
class PermanentDownloadException(message: String) : IOException(message)

/**
 * Downloads a single file to [download] with HTTP `Range` resume support.
 *
 * The IntelliJ platform's thin-client downloader (`CodeWithMeClientDownloader`) restarts
 * from byte 0 on every retry. On links that get cut mid-transfer (VPNs and corporate
 * proxies regularly close streams after a few seconds), a 700+ MB JetBrains Client
 * archive can therefore never complete: all retries fail and connecting to a workspace
 * reports "workspace IDE is not ready" although the download URL works fine in a browser
 * (browsers silently resume interrupted downloads).
 *
 * This downloader appends to a `.part` file and resumes with `Range` on every retry, so a
 * transfer only has to make *some* progress per attempt. Retries that made no progress
 * are limited; retries that made progress are unlimited (the user can cancel).
 */
class ResumableFileDownloader(
    private val httpClient: HttpClient = defaultHttpClient,
    private val maxZeroProgressAttempts: Int = MAX_ZERO_PROGRESS_ATTEMPTS,
    private val backoffFactory: () -> ExponentialBackoff =
        { ExponentialBackoff(initialMillis = 500, maxMillis = 10_000) },
    private val watchdogSchedulerFactory: () -> ScheduledExecutorService =
        { AppExecutorUtil.getAppScheduledExecutorService() },
) {

    companion object {
        private const val PART_SUFFIX = ".part"

        /** Report progress at most every this many bytes. */
        private const val PROGRESS_STEP_BYTES = 1024 * 1024L

        /** Retry limit for attempts that did not receive a single byte. */
        const val MAX_ZERO_PROGRESS_ATTEMPTS = 15

        /** Seconds without receiving any byte before the connection is considered stalled and re-established. */
        const val STALL_TIMEOUT_SECONDS = 90L

        /** Full refetch attempts when the downloaded content does not match its checksum. */
        const val MAX_CHECKSUM_RETRIES = 1

        /** Timeout for receiving the response (headers) of a single attempt. */
        private val RESPONSE_TIMEOUT = Duration.ofSeconds(60)

        private val CONTENT_RANGE_REGEX = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
        private const val START_GROUP = 1
        private const val TOTAL_GROUP = 3

        private val defaultHttpClient: HttpClient by lazy {
            IdeHttpProxy.configure(
                HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30))
            ).build()
        }

        /** The file that bytes are appended to while [download] is in progress. */
        internal fun partFileFor(targetFile: Path): Path =
            targetFile.resolveSibling(targetFile.fileName.toString() + PART_SUFFIX)

    }

    data class Progress(val downloadedBytes: Long, val totalBytes: Long?)

    /**
     * Downloads [url] to [targetFile]. An interrupted transfer is resumed from the bytes
     * already on disk until the file is complete; [targetFile] only appears once fully
     * downloaded (and, when [expectedChecksum] is given, once it matches the checksum).
     */
    suspend fun download(
        url: URI,
        targetFile: Path,
        expectedChecksum: Sha256Checksum? = null,
        onProgress: ((Progress) -> Unit)? = null,
        checkCancelled: (() -> Unit)? = null,
    ) {
        Files.createDirectories(targetFile.parent)
        val partFile = partFileFor(targetFile)
        val retries = RetryState()
        var checksumRetries = 0

        while (true) {
            val bytesBefore = fileSize(partFile)
            try {
                checkCancelled?.invoke()
                downloadAttempt(url, partFile, onProgress, checkCancelled)

                if (expectedChecksum != null && !expectedChecksum.matches(partFile)) {
                    Files.deleteIfExists(partFile)
                    if (checksumRetries >= MAX_CHECKSUM_RETRIES) {
                        throw PermanentDownloadException("Checksum mismatch for '$url'")
                    }
                    checksumRetries++
                    thisLogger().warn("Checksum mismatch for '$url', downloading again from scratch")
                    continue
                }

                moveAtomically(partFile, targetFile)
                onProgress?.invoke(Progress(fileSize(targetFile), fileSize(targetFile)))
                return
            } catch (e: Exception) {
                if (e.isCancellationException() || e is PermanentDownloadException) throw e
                retries.awaitNextAttempt(url, e, progressed = fileSize(partFile) > bytesBefore)
            }
        }
    }

    /** Executes a single download attempt, appending to (or truncating) [partFile]. */
    private suspend fun downloadAttempt(
        url: URI,
        partFile: Path,
        onProgress: ((Progress) -> Unit)?,
        checkCancelled: (() -> Unit)?,
    ) {
        val have = fileSize(partFile)

        // HttpClient.send blocks until the response headers arrive; run it interruptible
        val response: HttpResponse<InputStream> =
            runInterruptible(Dispatchers.IO) {
                httpClient.send(
                    createDownloadRequest(url, have),
                    HttpResponse.BodyHandlers.ofInputStream())
            }
        try {
            requireUsableStatus(url, response, partFile)
            val transfer = transferModeFor(url, response, partFile, have)
            copyBody(response, partFile, transfer, onProgress, checkCancelled)
        } finally {
            runCatching { response.body().close() }
        }
    }

    /**
     * Fails the attempt when [response] does not allow (re)starting the transfer. HTTP 416 is
     * special: the part file is at least as long as the remote file — either already complete
     * (validated on the next attempt) or corrupt — so it is deleted and the download restarts.
     * HTTP 408 and 429 signal congestion that a retry after backoff can fix, so they (like 5xx)
     * fail the attempt as retryable instead of permanent.
     */
    private fun requireUsableStatus(url: URI, response: HttpResponse<*>, partFile: Path) {
        val status = response.statusCode()
        when {
            status == HTTP_RANGE_NOT_SATISFIABLE ->
                restartCleanly(partFile, "HTTP $status for '$url', restarting download")
            status in HTTP_REDIRECTED..HTTP_CLIENT_ERROR_MAX
                    && !isRetryableClientError(status) ->
                throw PermanentDownloadException("HTTP $status for '$url'")
            status != HTTP_OK
                    && status != HTTP_PARTIAL_CONTENT ->
                throw IOException("HTTP $status for '$url'")
        }
    }

    /** Whether [status] is a 4xx that a retry can fix: 408/429 signal congestion, not "gone for good". */
    private fun isRetryableClientError(status: Int): Boolean =
        status == HTTP_REQUEST_TIMEOUT
                || status == HTTP_TOO_MANY_REQUESTS

    /** How the body of a response continues the bytes already in the part file. */
    private data class TransferMode(
        /** Whether to append the body to (rather than overwrite) the part file. */
        val append: Boolean,
        /** Bytes of the final file that the part file already holds; 0 when overwriting. */
        val existingBytes: Long,
        /** Total size of the complete file when known. */
        val totalBytes: Long?,
    )

    /**
     * Decides how to write the body of [response]: appended to the [have] bytes already on
     * disk when the server resumed exactly there, overwriting them when it did not. Deletes
     * the part file and fails the attempt when a clean restart is the only way forward.
     */
    private fun transferModeFor(url: URI, response: HttpResponse<*>, partFile: Path, have: Long): TransferMode {
        if (response.statusCode() == HTTP_PARTIAL_CONTENT) {
            val match = response.headers().firstValue("Content-Range").orElse(null)
                ?.let { CONTENT_RANGE_REGEX.matchEntire(it.trim()) }
            if (match != null) {
                val start = match.groupValues[START_GROUP].toLong()
                if (start != have) {
                    // server resumed at a different offset than requested; restart cleanly
                    restartCleanly(partFile, "Server resumed '$url' at byte $start but $have bytes are on disk")
                }
                return TransferMode(
                    append = true,
                    existingBytes = have,
                    totalBytes = match.groupValues[TOTAL_GROUP].toLong(),
                )
            }
            if (have > 0) {
                restartCleanly(partFile, "206 without usable Content-Range for '$url', restarting download")
            }
        } else if (have > 0) {
            // server ignored the Range header and sends the whole file: restart from scratch
            Files.deleteIfExists(partFile)
        }
        return TransferMode(
            append = false,
            existingBytes = 0,
            totalBytes = response.contentLength()
        )
    }

    private fun createDownloadRequest(url: URI, have: Long): HttpRequest {
        val requestBuilder = HttpRequest.newBuilder(url)
            .GET()
            .timeout(RESPONSE_TIMEOUT)
        if (have > 0) {
            requestBuilder.header("Range", "bytes=$have-")
        }
        return requestBuilder.build()
    }

    private fun copyBody(
        response: HttpResponse<InputStream>,
        partFile: Path,
        transfer: TransferMode,
        onProgress: ((Progress) -> Unit)?,
        checkCancelled: (() -> Unit)?,
    ) {
        val (append, existingBytes, total) = transfer
        val out = if (append) {
            Files.newOutputStream(
                partFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
            )
        } else {
            Files.newOutputStream(
                partFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
            )
        }

        StallWatchdog(
            timeoutMillis = TimeUnit.SECONDS.toMillis(STALL_TIMEOUT_SECONDS),
            checkCancelled = checkCancelled,
            scheduler = watchdogSchedulerFactory(),
        ).use { watchdog ->
            response.body().use { body ->
                watchdog.stream = body
                var written = existingBytes
                var lastReported = written - PROGRESS_STEP_BYTES
                val buffer = ByteArray(IO_BUFFER_SIZE)

                out.use { fileOut ->
                    while (true) {
                        checkCancelled?.invoke()
                        val bytesRead = readBody(body, buffer, watchdog)
                        if (bytesRead < 0) break
                        fileOut.write(buffer, 0, bytesRead)
                        written += bytesRead
                        watchdog.progress()

                        if (total != null && written > total) {
                            throw IOException("Received more bytes than expected for '${response.uri()}'")
                        }
                        if (onProgress != null && written - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = written
                            onProgress.invoke(Progress(written, total))
                        }
                    }
                }

                watchdog.rethrowIfCancelled()

                if (total != null && written != total) {
                    // stream cut before completion: retried with resume by the caller
                    throw IOException("Connection closed at byte $written. Expected $total bytes.")
                }
                onProgress?.invoke(Progress(written, total))
            }
        }
    }

    /**
     * Reads from [body], surfacing a stored watchdog cancellation instead of the [IOException]
     * that closing the stream produces.
     */
    private fun readBody(body: InputStream, buffer: ByteArray, watchdog: StallWatchdog): Int =
        try {
            body.read(buffer)
        } catch (e: IOException) {
            watchdog.rethrowIfCancelled()
            throw e
        }

    private fun fileSize(path: Path): Long = if (Files.exists(path)) Files.size(path) else 0L

    /** Deletes the partial file and fails the attempt, so the next one restarts from scratch. */
    private fun restartCleanly(partFile: Path, message: String): Nothing {
        Files.deleteIfExists(partFile)
        throw IOException(message)
    }

    /**
     * Retry bookkeeping for a single [download] call. Attempts that appended bytes to the part
     * file reset the backoff; attempts without any progress are counted. Gives up with an
     * [IOException] once [maxZeroProgressAttempts] attempts in a row made no progress.
     */
    private inner class RetryState {
        private val backoff = backoffFactory()
        private var zeroProgressAttempts = 0

        /**
         * Logs [cause] and suspends for the backoff delay so the caller can retry the download
         * from where it was interrupted.
         */
        suspend fun awaitNextAttempt(url: URI, cause: Exception, progressed: Boolean) {
            if (progressed) {
                zeroProgressAttempts = 0
                backoff.reset()
            } else if (++zeroProgressAttempts >= maxZeroProgressAttempts) {
                throw IOException(
                    "Failed to download '$url' after $zeroProgressAttempts attempts: ${cause.message}",
                    cause,
                )
            }
            val delayMillis = backoff.nextDelayMillis()
            thisLogger().warn(
                "Download of '$url' interrupted (${cause.message}); " +
                    "${if (progressed) "resuming" else "retrying"} in $delayMillis ms " +
                    "($zeroProgressAttempts/$maxZeroProgressAttempts)",
                cause,
            )
            @Suppress("ConvertLongToDuration")
            delay(delayMillis)
        }
    }

    /**
     * Closes the watched stream when no bytes arrived for [timeoutMillis], unblocking the reader.
     * Also polls [checkCancelled] so a blocked read does not delay user cancellation.
     * Schedules its checks on the given shared [scheduler]; [close] cancels the task but does
     * not shut the scheduler down.
     */
    private class StallWatchdog(
        private val timeoutMillis: Long,
        private val checkCancelled: (() -> Unit)?,
        scheduler: ScheduledExecutorService,
    ) : AutoCloseable {
        @Volatile
        var stream: InputStream? = null

        @Volatile
        private var lastProgressAt = System.currentTimeMillis()

        @Volatile
        private var cancellation: Throwable? = null

        private val pollIntervalMillis =
            minOf(CANCELLATION_POLL_INTERVAL_MILLIS, timeoutMillis / 2).coerceAtLeast(1L)

        private val task: ScheduledFuture<*> = scheduler.scheduleWithFixedDelay(
            { check() },
            pollIntervalMillis,
            pollIntervalMillis,
            TimeUnit.MILLISECONDS,
        )

        fun progress() {
            lastProgressAt = System.currentTimeMillis()
        }

        fun rethrowIfCancelled() {
            cancellation?.let { throw it }
        }

        private fun check() {
            if (cancellation != null) return

            val failure = runCatching { checkCancelled?.invoke() }.exceptionOrNull()
            if (failure != null) {
                cancellation = failure
                runCatching { stream?.close() } // unblocks the reader immediately
                return
            }

            if (System.currentTimeMillis() - lastProgressAt >= timeoutMillis) {
                runCatching { stream?.close() } // unblocks the reader; the caller retries with resume
            }
        }

        override fun close() {
            task.cancel(false)
        }
    }
}

