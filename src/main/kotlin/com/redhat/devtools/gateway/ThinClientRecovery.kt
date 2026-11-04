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

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.isAlive
import com.redhat.devtools.gateway.util.WorkspaceSessionProgress
import com.redhat.devtools.gateway.util.delayRespectingProgress
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.util.updateProgress
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * How long [ThinClientRecovery] may wait for a replacement thin client to become present.
 *
 * Production default is intentionally large: the JetBrains Client may still be downloading or
 * extracting (same reason the initial connect wait has no short wall-clock limit). Tests pass a
 * short [timeout]. Cancel / a dead handle still end the wait promptly.
 */
internal data class HealVerification(
    val timeout: Duration = 24.hours,
    val poll: Duration = 1.seconds,
) {
    init {
        require(timeout > Duration.ZERO) { "timeout must be positive" }
        require(poll > Duration.ZERO) { "poll must be positive" }
    }
}

/**
 * Thin-client lifecycle for a pod-roll reconnect (CRW-13910): closes the stale client and starts
 * a verified replacement on the session's existing local port.
 *
 * Healing via `updateJoinLink` + `notifyReconnect` is intentionally not used: it only restores
 * gateway websockets (`clientPresent`) and does not reliably relaunch a usable Guest UI after a
 * pod roll.
 *
 * A started replacement only counts as success once it became present within [HealVerification.timeout]
 * — a client that never comes up is closed and reported as failure, so the caller can retry.
 *
 * Every handle closed on the reconnect's behalf is registered in
 * [ThinClientSessionContext.handlesClosedByReconnect] *before* closing, so the resulting close
 * events are ignored by [DevSpacesConnection.onThinClientClosed] whenever they fire.
 */
@Suppress("UnstableApiUsage", "TooGenericExceptionCaught") // boundary: failures are contained, not escaped
internal class ThinClientRecovery(
    private val sessionCtx: ThinClientSessionContext,
    private val startThinClient: suspend (String, ThinClientSessionContext) -> ThinClientHandle,
    private val onClientHandleReplaced: (ThinClientHandle) -> Unit,
    private val verification: HealVerification,
    private val checkCanceled: () -> Unit,
) {

    /** The session's live thin client handle, or null when no live client exists. */
    fun currentHandle(): ThinClientHandle? =
        sessionCtx.thinClient?.takeIf { it.lifetime.isAlive }

    /**
     * Starts a replacement client on the session's local port and verifies that it came up.
     *
     * A client that fails verification is closed (and marked as closed by the reconnect) before
     * the failure is rethrown, so a retry does not stack half-started clients.
     */
    suspend fun startNewClient(joinLink: String, indicator: ProgressIndicator?) {
        val newClient = startThinClient(joinLink, sessionCtx)
        sessionCtx.thinClient = newClient
        onClientHandleReplaced(newClient)
        try {
            awaitClientStarted(newClient, indicator)
        } catch (e: Exception) {
            closeAsReconnectInternal(newClient)
            throw e
        }
    }

    /** Waits until [handle] is present, its lifetime ends, or [HealVerification.timeout] elapses. */
    private suspend fun awaitClientStarted(handle: ThinClientHandle, indicator: ProgressIndicator?) {
        var remaining = verification.timeout
        while (remaining > Duration.ZERO) {
            checkCanceled()
            if (handle.clientPresent) {
                return
            }
            if (!handle.lifetime.isAlive) {
                break
            }
            val poll = minOf(verification.poll, remaining)
            delayRespectingProgress(indicator, poll)
            remaining -= poll
        }
        throw IOException("Replacement thin client did not start within ${verification.timeout}")
    }

    /**
     * Closes the stale client(s), then starts and verifies a new thin client on the given join link.
     *
     * @return true when the replacement client started and came up, false when it failed (caller tears down)
     */
    suspend fun replaceClient(joinLink: String, indicator: ProgressIndicator?): Boolean =
        try {
            indicator?.updateProgress(
                WorkspaceSessionProgress.CLOSING_IDE, WorkspaceSessionProgress.CLOSING_FRACTION
            )
            closeOldClients()
            indicator?.updateProgress(
                WorkspaceSessionProgress.CONNECTING_TO_IDE, WorkspaceSessionProgress.CONNECTED_FRACTION
            )
            startNewClient(joinLink, indicator)
            true
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            thisLogger().warn("Pod roll: replacement thin client start failed: ${e.message}", e)
            false
        }

    private fun closeOldClients() {
        val handles = linkedSetOf<ThinClientHandle>()
        currentHandle()?.let { handles.add(it) }
        sessionCtx.thinClient?.let { handles.add(it) }
        handles.forEach(::closeAsReconnectInternal)
    }

    /**
     * Closes [handle] on the reconnect's behalf. The handle is marked first so the close
     * event is ignored by [DevSpacesConnection.onThinClientClosed] — whenever it fires.
     * A handle closed here is no longer the session's client, so repeat events for it are
     * recognized as stale (see [ThinClientSessionContext.consumeReconnectOwnedClose]).
     */
    private fun closeAsReconnectInternal(handle: ThinClientHandle) {
        sessionCtx.handlesClosedByReconnect.add(handle)
        if (sessionCtx.thinClient === handle) {
            sessionCtx.thinClient = null
        }
        closeQuietly(handle)
    }

    private fun closeQuietly(handle: ThinClientHandle) {
        try {
            handle.close()
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            thisLogger().debug("Failed to close old thin client during reconnect", e)
        }
    }
}
