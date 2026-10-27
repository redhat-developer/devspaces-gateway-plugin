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
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.util.podIdentity
import com.redhat.devtools.gateway.util.podLogIdentity
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1Pod
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks the workspace pod identity (UID) and detects pod rolls.
 *
 * ## Session recovery routing
 *
 * When the pod UID changes:
 *
 * 1. If [isWorkspaceRestartInProgress] is true ([DevWorkspace.RESTART_KEY] on the DevWorkspace),
 *    return [PodResolution.RestartSuppressed] and do **not** invoke [onPodRoll].
 *    [com.redhat.devtools.gateway.devworkspace.DevWorkspaceRestart] handles recovery.
 *    The previous identity is kept: if the annotation clears without
 *    [com.redhat.devtools.gateway.devworkspace.DevWorkspaceRestart] owning recovery, a later
 *    resolve still detects the roll and replays it as a safety net.
 * 2. Otherwise invoke [onPodRoll] (typically [ThinClientReconnect]) and return [PodResolution.RollDelegated].
 *
 * Port-forward timing and progress are handled by [PortForwardPodResolver], not here.
 *
 * @param isWorkspaceRestartInProgress whether a user-initiated restart annotation is set; when true,
 *   pod-roll callbacks are suppressed.
 */
internal class WorkspacePodTracker(
    private val remoteIdeServer: RemoteIDEServer,
    private val isWorkspaceRestartInProgress: () -> Boolean = { false },
) {

    /**
     * Identity (UID) of the pod the session is connected to; committed atomically on resolve,
     * except when a roll is suppressed for an annotated restart (previous identity kept, see
     * [routeRoll]).
     */
    private val connectedPod = AtomicReference<String?>(null)

    /**
     * Invoked when a pod roll is detected and [isWorkspaceRestartInProgress] is false.
     * Wired by [DevSpacesConnection.setupThinClientReconnect] to [ThinClientReconnect.execute].
     */
    var onPodRoll: suspend (V1Pod) -> Unit = {}

    fun seed(pod: V1Pod) {
        connectedPod.set(podIdentity(pod))
    }

    /**
     * Refreshes the workspace pod and applies pod-roll routing (see class KDoc).
     */
    suspend fun resolvePod(): PodResolution {
        val pod = runCatching { remoteIdeServer.refreshPod() }
            .onFailure { e -> logPodRefreshFailure(e) }
            .getOrNull() ?: return PodResolution.Unavailable
        return routeRoll(pod)
    }

    private suspend fun routeRoll(pod: V1Pod): PodResolution {
        val identity = podIdentity(pod)
        val previousIdentity = connectedPod.get()
        val isRoll = isPodRoll(previousIdentity, identity)
        if (isRoll && isWorkspaceRestartInProgress()) {
            thisLogger().info(
                "resolvePod: ${DevWorkspace.RESTART_KEY} is set; " +
                    "skipping pod-roll reconnect (DevWorkspaceRestart handles this)"
            )
            // keep the previous identity: if the annotation clears without DevWorkspaceRestart
            // owning recovery, a later resolve must still detect — and replay — this roll
            return PodResolution.RestartSuppressed
        }
        if (!isRoll) {
            connectedPod.set(identity)
        }
        // a concurrent resolve may already have committed — and routed — this roll
        val routedConcurrently = isRoll && !connectedPod.compareAndSet(previousIdentity, identity)
        return when {
            !isRoll || routedConcurrently -> PodResolution.Ready(pod)
            else -> {
                thisLogger().info(
                    "resolvePod: pod roll $previousIdentity -> $identity (${podLogIdentity(pod)}), " +
                        "starting reconnect"
                )
                onPodRoll(pod)
                PodResolution.RollDelegated(pod)
            }
        }
    }

    private fun isPodRoll(previousIdentity: String?, identity: String?): Boolean =
        previousIdentity != null && identity != null && identity != previousIdentity

    private fun logPodRefreshFailure(e: Throwable) {
        when {
            e is IOException && e.message?.contains("not running", ignoreCase = true) == true ->
                thisLogger().info("Workspace pod not ready yet, will retry: ${e.message}")
            e is ApiException ->
                thisLogger().warn("Failed to refresh workspace pod: ${e.message}", e)
            else ->
                thisLogger().info("Failed to refresh workspace pod, will retry: ${e.message}")
        }
    }
}
