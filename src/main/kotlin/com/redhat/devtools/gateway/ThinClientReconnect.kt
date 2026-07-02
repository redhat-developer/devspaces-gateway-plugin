/*
 * Copyright (c) 2024-2025 Red Hat, Inc.
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reconnects the thin client after an **unplanned** pod roll (CRW-11119).
 *
 * ## Session recovery routing
 *
 * Invoked only from [WorkspacePodTracker.onPodRoll] when the pod UID changes and
 * [DevWorkspacePatch.RESTART_KEY] is **not** set. User-initiated restarts are routed to
 * [com.redhat.devtools.gateway.devworkspace.DevWorkspaceRestart] instead; the tracker suppresses
 * this handler while that annotation is present.
 *
 * Keeps the existing session: same local port, port forwarder, and [ThinClientSessionContext].
 * Progress title: "Reconnecting to workspace" ([PROGRESS_TITLE]).
 *
 * Closes the old thin client, waits for the new pod's IDE via [RemoteIDEServer.awaitJoinLink],
 * and starts a new thin client on the same forwarder.
 *
 * Transient failures are retried up to [MAX_RETRIES] times. Permanent failures, exhausted retries,
 * or user cancellation tear down the session via [onClientClosed].
 */
internal class ThinClientReconnect(
    private val remoteIdeServer: RemoteIDEServer,
    private val sessionCtx: ThinClientSessionContext,
    private val getCurrentClient: () -> ThinClientHandle?,
    private val startThinClient: suspend (String, ThinClientSessionContext) -> ThinClientHandle,
    private val onClientHandleReplaced: (ThinClientHandle) -> Unit,
    private val onClientClosed: (ThinClientHandle?, ThinClientSessionContext) -> Unit,
) {

    companion object {
        private const val MAX_RETRIES: Int = 3
        private const val PROGRESS_TITLE: String = "Reconnecting to workspace"
        private val RETRY_DELAY: Duration = 10.seconds
        private val RECONNECT_CLEANUP_DRAIN_DELAY: Duration = 500.milliseconds
    }

    fun execute(pod: V1Pod) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            null, PROGRESS_TITLE, true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                runBlocking {
                    onPodRoll(pod, indicator)
                }
            }
        })
    }

    suspend fun onPodRoll(pod: V1Pod, indicator: ProgressIndicator? = null) {
        if (!sessionCtx.reconnecting.compareAndSet(false, true)) {
            thisLogger().warn(
                "Pod roll: reconnect already in progress, skipping duplicate for ${podLogIdentity(pod)}"
            )
            return
        }

        thisLogger().info(
            "Pod roll: reconnecting IDE session for ${podLogIdentity(pod)} via local port ${sessionCtx.localPort}"
        )

        try {
            indicator?.updateProgress(WorkspaceSessionProgress.CLOSING_IDE, 0.0)
            closeOldThinClient()
            indicator?.let { checkProgressCanceled(it) }
            reconnectWithRetries(pod, indicator)
        } catch (e: Exception) {
            if (e.isCancellationException()) {
                abortReconnect()
                throw e
            }
            sessionCtx.reconnecting.set(false)
        } finally {
            indicator.clearProgressText2Safely()
        }
    }

    /**
     * Attempts to reconnect up to [MAX_RETRIES] times.
     * Returns on success (reconnecting cleared inside), calls [onClientClosed] on permanent
     * failure or exhausted retries (reconnecting cleared before the call so cleanup runs).
     */
    private suspend fun reconnectWithRetries(pod: V1Pod, indicator: ProgressIndicator?) {
        var lastError: Exception? = null

        for (attempt in 0 until MAX_RETRIES) {
            try {
                thisLogger().info(
                    "Pod roll: reconnect attempt ${attempt + 1}/$MAX_RETRIES — waiting for IDE on ${podLogIdentity(pod)}"
                )
                indicator?.updateProgress(WorkspaceSessionProgress.WAITING_FOR_IDE, 0.5)
                indicator?.setProgressText2("Attempt ${attempt + 1}/$MAX_RETRIES")
                val joinLink = fetchJoinLink(pod, indicator)
                thisLogger().info(
                    "Pod roll: IDE ready on attempt ${attempt + 1}, starting thin client on local port ${sessionCtx.localPort}"
                )
                indicator?.updateProgress(WorkspaceSessionProgress.CONNECTING_TO_IDE, 1.0)
                indicator?.setProgressText2(null)
                val newClient = startThinClient(joinLink, sessionCtx)
                onClientHandleReplaced(newClient)

                delay(RECONNECT_CLEANUP_DRAIN_DELAY)
                thisLogger().info("Pod roll: IDE session reconnected successfully after ${attempt + 1} attempt(s)")
                sessionCtx.reconnecting.set(false)
                return
            } catch (e: Exception) {
                when (val decision = classifyError(e)) {
                    null -> throw e
                    else -> {
                        lastError = e
                        logReconnectAttemptFailure(attempt, e, decision.permanent)
                        if (decision.permanent) break
                        if (decision.willRetry && attempt < MAX_RETRIES - 1) {
                            delayRespectingProgress(indicator, RETRY_DELAY)
                        }
                    }
                }
            }
        }

        failReconnect(lastError ?: IOException("Reconnect failed after $MAX_RETRIES attempts"))
    }

    private fun abortReconnect() {
        sessionCtx.reconnecting.set(false)
        onClientClosed(getCurrentClient(), sessionCtx)
    }

    private fun failReconnect(error: Exception) {
        sessionCtx.reconnecting.set(false)
        logReconnectFailure(error)
        onClientClosed(getCurrentClient(), sessionCtx)
    }

    private suspend fun fetchJoinLink(pod: V1Pod, indicator: ProgressIndicator?): String {
        indicator?.let { checkProgressCanceled(it) }
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
        return remoteIdeServer.awaitJoinLink(checkCancelled = checkCancelled)
    }

    private data class RetryDecision(
        val permanent: Boolean,
        val willRetry: Boolean
    )

    private fun classifyError(e: Exception): RetryDecision? = when {
        e.isCancellationException() -> null
        e.isTimeoutException() -> RetryDecision(permanent = false, willRetry = true)
        else -> {
            val permanent = when {
                e is IOException && e.message?.contains("no join link", ignoreCase = true) == true -> true
                e is IllegalStateException && e.message?.contains("not ready", ignoreCase = true) == true -> true
                e is ApiException && e.code in setOf(401, 403, 404) -> true
                else -> false
            }
            RetryDecision(permanent = permanent, willRetry = !permanent)
        }
    }

    private suspend fun closeOldThinClient() {
        try {
            getCurrentClient()?.close()
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            thisLogger().debug("Failed to close old thin client during reconnect", e)
        }
    }

    private fun logReconnectAttemptFailure(attempt: Int, e: Exception, permanent: Boolean) {
        val kind = when {
            e.isTimeoutException() -> "timeout"
            permanent -> "permanent"
            else -> "transient"
        }
        val retryHint = if (!permanent && attempt < MAX_RETRIES - 1) ", will retry" else ""
        thisLogger().warn(
            "Pod roll: reconnect attempt ${attempt + 1}/$MAX_RETRIES failed ($kind): " +
                "${e.javaClass.simpleName}: ${e.message}$retryHint"
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
