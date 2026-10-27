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
package com.redhat.devtools.gateway

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.util.WorkspaceSessionProgress
import com.redhat.devtools.gateway.util.checkProgressCanceled
import com.redhat.devtools.gateway.util.clearProgressText2Safely
import com.redhat.devtools.gateway.util.delayRespectingProgress
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.util.isTimeoutException
import com.redhat.devtools.gateway.util.podLogIdentity
import com.redhat.devtools.gateway.util.setProgressText2
import com.redhat.devtools.gateway.util.updateProgress
import com.redhat.devtools.gateway.view.ui.Dialogs
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reconnects the thin client after an **unplanned** pod roll (CRW-13910).
 *
 * Always **replaces** the thin client: closes the old handle and starts a new one on the same
 * local port / port forwarder / [ThinClientSessionContext]. Healing via
 * `updateJoinLink` + `notifyReconnect` only restores gateway websockets (`clientPresent`) and
 * does not relaunch the Guest window after a pod roll — the window stays closed while the
 * session looks connected.
 *
 * Client lifecycle and start verification live in [ThinClientRecovery]. Progress title:
 * "Reconnecting to workspace". Permanent failures, exhausted retries, or user cancellation
 * tear down the session via [onClientClosed].
 *
 * [cancel] aborts an in-flight reconnect when the session ends underneath it (e.g. the user
 * closes the client while recovery runs), so no replacement client is spawned for a dead
 * session. It is wired to [ThinClientSessionContext.cancelReconnect].
 */
@Suppress("TooGenericExceptionCaught") // boundary: failures must fail over, not escape the task
internal class ThinClientReconnect(
    private val remoteIdeServer: RemoteIDEServer,
    private val sessionCtx: ThinClientSessionContext,
    private val startThinClient: suspend (String, ThinClientSessionContext) -> ThinClientHandle,
    private val onClientHandleReplaced: (ThinClientHandle) -> Unit,
    private val onClientClosed: (ThinClientHandle?, ThinClientSessionContext) -> Unit,
    private val verification: HealVerification = HealVerification(),
) {

    /** Client lifecycle for the reconnect: heal, replace, and verify thin clients. */
    private val recovery = ThinClientRecovery(
        sessionCtx = sessionCtx,
        startThinClient = startThinClient,
        onClientHandleReplaced = onClientHandleReplaced,
        verification = verification,
        checkCanceled = ::checkNotCanceled,
    )

    /** True once [cancel] was called; checked between all waits so a canceled reconnect stops promptly. */
    private val canceled = AtomicBoolean(false)

    @Volatile
    private var currentIndicator: ProgressIndicator? = null

    companion object {
        private const val MAX_RETRIES: Int = 3
        private const val PROGRESS_TITLE: String = "Reconnecting to workspace"

        private val RETRY_DELAY: Duration = 10.seconds

        private val PERMANENT_API_ERROR_CODES: Set<Int> = setOf(401, 403, 404)
    }

    /** Runs the reconnect as a cancelable background task with progress. */
    fun execute(pod: V1Pod) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, PROGRESS_TITLE, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                currentIndicator = indicator
                try {
                    runBlocking { onPodRoll(pod, indicator) }
                } finally {
                    currentIndicator = null
                }
            }
        })
    }

    /**
     * Aborts an in-flight or queued reconnect: cancels the progress indicator and flags the
     * reconnect so it stops at the next wait point. The reconnect then ends via its cancellation
     * path, which tears the session down (idempotent with the caller's own teardown).
     */
    fun cancel() {
        canceled.set(true)
        currentIndicator?.cancel()
    }

    suspend fun onPodRoll(pod: V1Pod, indicator: ProgressIndicator? = null) {
        if (canceled.get()) {
            thisLogger().info("Pod roll: reconnect canceled before it started")
            return
        }
        if (!sessionCtx.reconnecting.compareAndSet(false, true)) {
            thisLogger().warn(
                "Pod roll: reconnect already in progress, skipping duplicate for ${podLogIdentity(pod)}"
            )
            return
        }

        thisLogger().info(
            "Pod roll: replacing IDE session for ${podLogIdentity(pod)} via local port ${sessionCtx.localPort}"
        )
        sessionCtx.throwIfReconnectCanceled = ::checkNotCanceled

        try {
            reconnectWithRetries(pod, indicator)
        } catch (e: Exception) {
            if (e.isCancellationException()) {
                endReconnect(null)
                throw e
            }
            thisLogger().warn(
                "Pod roll: unexpected reconnect failure for ${podLogIdentity(pod)}: ${e.message}", e
            )
            endReconnect(e)
        } finally {
            sessionCtx.throwIfReconnectCanceled = {}
            indicator.clearProgressText2Safely()
        }
    }

    /**
     * Fetches a join link and starts a replacement thin client, retrying transient failures
     * up to [MAX_RETRIES] times. Permanent failures or exhausted retries tear down the session.
     */
    private suspend fun reconnectWithRetries(pod: V1Pod, indicator: ProgressIndicator?) {
        var lastError: Exception? = null

        for (attempt in 0 until MAX_RETRIES) {
            checkNotCanceled()
            try {
                val joinLink = fetchJoinLink(pod, indicator, attempt)
                if (recovery.currentHandle() == null) {
                    thisLogger().info("Pod roll: no thin client handle, starting a new client")
                    recovery.startNewClient(joinLink, indicator)
                } else if (!recovery.replaceClient(joinLink, indicator)) {
                    throw IOException("Replacement thin client did not start")
                }
                thisLogger().info(
                    "Pod roll: IDE session reconnected with a new thin client after ${attempt + 1} attempt(s)"
                )
                sessionCtx.reconnecting.set(false)
                return
            } catch (e: Exception) {
                if (e.isCancellationException()) throw e
                lastError = e
                val permanent = isPermanentError(e)
                if (permanent || attempt == MAX_RETRIES - 1) {
                    break
                }
                logReconnectAttemptFailure(attempt, e, permanent)
                delayRespectingProgress(indicator, RETRY_DELAY)
            }
        }

        endReconnect(lastError ?: IOException("Reconnect failed after $MAX_RETRIES attempts"))
    }

    /**
     * Prepares one reconnect attempt: reports progress, fetches the pod's join link (seeding the
     * rolled pod when the refresh fails), and switches the progress to the connect phase.
     */
    private suspend fun fetchJoinLink(pod: V1Pod, indicator: ProgressIndicator?, attempt: Int): String {
        indicator?.updateProgress(
            WorkspaceSessionProgress.WAITING_FOR_IDE, WorkspaceSessionProgress.WAITING_FRACTION
        )
        indicator?.setProgressText2("Attempt ${attempt + 1}/$MAX_RETRIES")
        thisLogger().info(
            "Pod roll: reconnect attempt ${attempt + 1}/$MAX_RETRIES — waiting for IDE on ${podLogIdentity(pod)}"
        )
        val checkCancelled = indicator?.let { ind -> { checkProgressCanceled(ind) } }
        try {
            remoteIdeServer.refreshPod()
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            thisLogger().info(
                "Pod roll: refresh failed, using rolled pod ${podLogIdentity(pod)}: ${e.message}"
            )
            remoteIdeServer.setPod(pod)
        }
        val joinLink = remoteIdeServer.awaitJoinLink(checkCancelled = checkCancelled)
        indicator?.updateProgress(
            WorkspaceSessionProgress.CONNECTING_TO_IDE, WorkspaceSessionProgress.CONNECTED_FRACTION
        )
        indicator?.setProgressText2(null)
        return joinLink
    }

    private fun checkNotCanceled() {
        if (canceled.get()) {
            throw CancellationException("Pod-roll reconnect canceled")
        }
    }

    /**
     * Ends the reconnect: clears the reconnecting flag, reports [error] when the reconnect failed
     * (null means user cancellation), and tears the session down via [onClientClosed].
     */
    private fun endReconnect(error: Exception?) {
        sessionCtx.reconnecting.set(false)
        error?.let { logReconnectFailure(it) }
        onClientClosed(recovery.currentHandle(), sessionCtx)
    }

    /**
     * Permanent failures abort the retry loop immediately: authentication, permission, or
     * not-found API errors. A missing join link is deliberately absent — the ready →
     * join-link transition can flap right after a pod roll, so it retries like any
     * transient failure and only fails the session once the retries are exhausted.
     */
    private fun isPermanentError(e: Exception): Boolean = when {
        e.isTimeoutException() -> false
        e is ApiException && e.code in PERMANENT_API_ERROR_CODES -> true
        else -> false
    }

    private fun logReconnectAttemptFailure(attempt: Int, e: Exception, permanent: Boolean) {
        val kind = when {
            e.isTimeoutException() -> "timeout"
            permanent -> "permanent"
            else -> "transient"
        }
        thisLogger().warn(
            "Pod roll: reconnect attempt ${attempt + 1}/$MAX_RETRIES failed ($kind): " +
                "${e.javaClass.simpleName}: ${e.message}, retrying in $RETRY_DELAY"
        )
    }

    private fun logReconnectFailure(e: Exception) {
        val message = e.message ?: "Unknown error during reconnect"
        if (e.isTimeoutException()) {
            thisLogger().warn("Reconnect failed after $MAX_RETRIES attempts: $message")
        } else {
            thisLogger().warn("Reconnect failed after $MAX_RETRIES attempts: $message", e)
        }
        val application = ApplicationManager.getApplication() ?: return
        application.invokeLater {
            Dialogs.error(message, "Connection Error")
        }
    }
}
