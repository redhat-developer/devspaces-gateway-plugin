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

import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.devworkspace.DevWorkspacePatch
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.util.podIdentity
import com.redhat.devtools.gateway.util.podLogIdentity
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1Pod
import java.io.IOException

/**
 * Tracks the workspace pod identity (UID) and detects pod rolls.
 *
 * ## Session recovery routing
 *
 * When the pod UID changes:
 *
 * 1. If [isWorkspaceRestartInProgress] is true ([DevWorkspacePatch.RESTART_KEY] on the DevWorkspace),
 *    return [PodResolution.RestartSuppressed] and do **not** invoke [onPodRoll].
 *    [com.redhat.devtools.gateway.devworkspace.DevWorkspaceRestart] handles recovery.
 * 2. Otherwise invoke [onPodRoll] (typically [ThinClientReconnect]) and return [PodResolution.RollDelegated].
 *
 * Port-forward timing and progress are handled by [PortForwardPodResolver], not here.
 *
 * @param isWorkspaceRestartInProgress whether a user-initiated restart annotation is set; when true,
 *   pod-roll callbacks are suppressed.
 */
class WorkspacePodTracker(
    private val remoteIdeServer: RemoteIDEServer,
    private val isWorkspaceRestartInProgress: () -> Boolean = { false },
) {
    private var connectedPod: String? = null

    /**
     * Invoked when a pod roll is detected and [isWorkspaceRestartInProgress] is false.
     * Wired by [DevSpacesConnection.setupThinClientReconnect] to [ThinClientReconnect.execute].
     */
    var onPodRoll: suspend (V1Pod) -> Unit = {}

    fun seed(pod: V1Pod) { connectedPod = podIdentity(pod) }

    /**
     * Refreshes the workspace pod and applies pod-roll routing (see class KDoc).
     */
    suspend fun resolvePod(): PodResolution {
        val pod = runCatching { remoteIdeServer.refreshPod() }
            .onFailure { e ->
                logPodRefreshFailure(e)
                thisLogger().warn("resolvePod: workspace pod not available: ${e.message}")
            }
            .getOrNull()
            ?: return PodResolution.Unavailable

        val identity = podIdentity(pod)
        val previousIdentity = connectedPod
        connectedPod = identity

        if (previousIdentity != null
            && identity != null
            && identity != previousIdentity) {
            thisLogger().info(
                "resolvePod: pod roll $previousIdentity -> $identity (${podLogIdentity(pod)}), starting reconnect"
            )
            if (isWorkspaceRestartInProgress()) {
                thisLogger().info(
                    "resolvePod: ${DevWorkspacePatch.RESTART_KEY} is set; " +
                        "skipping pod-roll reconnect (DevWorkspaceRestart handles this)"
                )
                return PodResolution.RestartSuppressed
            }
            onPodRoll(pod)
            return PodResolution.RollDelegated(pod)
        }

        return PodResolution.Ready(pod)
    }

    private fun logPodRefreshFailure(e: Throwable) {
        when (e) {
            is ApiException ->
                thisLogger().warn("Failed to refresh workspace pod: ${e.message}", e)
            is IOException if e.message?.contains("not running", ignoreCase = true) == true ->
                thisLogger().info("Workspace pod not ready yet, will retry: ${e.message}")
            else ->
                thisLogger().info("Failed to refresh workspace pod, will retry: ${e.message}", e)
        }
    }
}
