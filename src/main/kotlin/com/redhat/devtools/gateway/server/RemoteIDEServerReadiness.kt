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
package com.redhat.devtools.gateway.server

import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.util.ExponentialBackoff
import com.redhat.devtools.gateway.util.isCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.IOException

/**
 * Polls a state check with exponential backoff until the expected state is reached
 * or the given timeout elapses.
 *
 * @param targetDescription Human-readable description of the waited target, used in log messages.
 * @param isReady Returns whether the server is currently ready. Non-terminal exceptions are
 * caught and treated as "not ready"; [CancellationException] and
 * [ServerContainerNotFoundException] are rethrown.
 * @param refresh Target re-resolution before each readiness probe (only used when waiting
 * for ready). Failures are retried with a warning after [REFRESH_FAILURE_WARNING_THRESHOLD]
 * consecutive failures. Terminal exceptions are rethrown.
 * @param backoff Delay sequence between probes. A successful refresh must NOT reset it:
 * while the target is still not ready the delays keep growing (500ms, 1s, 2s, ... capped)
 * instead of polling at a constant rate.
 */
class RemoteIDEServerReadiness(
    private val targetDescription: () -> String,
    private val isReady: suspend (checkCancelled: (() -> Unit)?) -> Boolean,
    private val refresh: (() -> Unit)? = null,
    private val backoff: ExponentialBackoff = ExponentialBackoff(),
) {
    /**
     * Waits until [isReady] reports the expected state.
     *
     * @param isReadyState True if the server becoming ready is expected, false if termination is expected.
     * @param timeout Maximum waiting period in seconds.
     * @param checkCancelled Optional user-cancellation check invoked before every probe.
     * @return True if the expected state is achieved within the timeout, false otherwise.
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun waitFor(
        isReadyState: Boolean,
        timeout: Long,
        checkCancelled: (() -> Unit)? = null,
    ): Boolean =
        @Suppress("ConvertLongToDuration")
        withTimeoutOrNull(timeout * MILLISECONDS_PER_SECOND) {
            logWaitingForState(isReadyState, timeout)
            val refreshFailures = intArrayOf(0)
            var pollCount = 0
            var elapsedMillis = 0L
            while (true) {
                checkCancelled?.invoke()
                // On a transient refresh failure the probe is skipped for this
                // iteration, same as the old refreshPodBeforeCheck behavior.
                val probeAllowed = skipCheck(isReadyState) || attemptRefresh(refreshFailures)
                if (probeAllowed) {
                    val stateReached = try {
                        isReady(checkCancelled) == isReadyState
                    } catch (e: Exception) {
                        if (e.isCancellationException() || e is ServerContainerNotFoundException) throw e
                        thisLogger().debug("Failed to check ${targetDescription()} state.", e)
                        false
                    }
                    if (stateReached) {
                        logStateReached(isReadyState, elapsedMillis)
                        return@withTimeoutOrNull true
                    }
                }

                pollCount++
                logStillWaiting(pollCount, elapsedMillis, timeout)
                yield()
                val delayMillis = backoff.nextDelayMillis()
                elapsedMillis += delayMillis
                delay(delayMillis)
            }

            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false

    private fun skipCheck(isReadyState: Boolean): Boolean = !isReadyState || refresh == null

    private fun attemptRefresh(refreshFailures: IntArray): Boolean {
        val doRefresh = refresh ?: return true
        return try {
            doRefresh()
            refreshFailures[0] = 0
            true
        } catch (e: Exception) {
            if (e.isCancellationException() || e is ServerContainerNotFoundException) throw e
            refreshFailures[0]++
            thisLogger().debug("Failed to refresh ${targetDescription()} during state check", e)
            if (refreshFailures[0] == REFRESH_FAILURE_WARNING_THRESHOLD) {
                thisLogger().warn(
                    "Refresh of ${targetDescription()} has failed ${refreshFailures[0]} consecutive times; " +
                        "stale references may cause incorrect state checks"
                )
            }
            false
        }
    }

    private fun logWaitingForState(isReadyState: Boolean, timeout: Long) {
        thisLogger().info(
            "Waiting for ${targetDescription()} to ${if (isReadyState) "become ready" else "terminate"}; " +
                "timeout: ${timeout}s."
        )
    }

    private fun logStateReached(isReadyState: Boolean, elapsedMillis: Long) {
        thisLogger().info(
            "${targetDescription()} ${if (isReadyState) "is ready" else "terminated"} after ${elapsedMillis}ms."
        )
    }

    private fun logStillWaiting(pollCount: Int, elapsedMillis: Long, timeout: Long) {
        if (pollCount % STILL_WAITING_LOG_INTERVAL != 0) {
            return
        }
        thisLogger().debug(
            "Still waiting for ${targetDescription()} " +
                "(${elapsedMillis}ms / ${timeout * MILLISECONDS_PER_SECOND}ms)."
        )
    }

    companion object {
        private const val MILLISECONDS_PER_SECOND = 1000L
        private const val STILL_WAITING_LOG_INTERVAL = 10

        /** Number of consecutive refresh failures before emitting a warning. */
        private const val REFRESH_FAILURE_WARNING_THRESHOLD = 10
    }
}
