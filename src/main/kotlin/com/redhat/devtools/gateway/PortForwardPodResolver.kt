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
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import com.redhat.devtools.gateway.openshift.PodForwardResolution
import com.redhat.devtools.gateway.util.podLogIdentity
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Adapts [WorkspacePodTracker] outcomes to port-forward transport and recovery progress.
 *
 * While no pod is available, [resolve] polls until a running pod appears before returning.
 * On pod roll, the rolled pod is returned immediately so port-forward can re-establish in
 * parallel with [ThinClientReconnect] (IDE wait via exec, then thin client via local port).
 */
internal class PortForwardPodResolver(
    private val tracker: WorkspacePodTracker,
    private val sessionCtx: ThinClientSessionContext,
    private val forwardRecovery: ForwardRecoveryProgress,
) {
    companion object {
        private val POD_POLL_DELAY = DevWorkspacePods.DEFAULT_RECONNECT_DELAY_SECONDS.seconds
    }

    suspend fun resolve(): PodForwardResolution {
        return when (val result = tracker.resolvePod()) {
            is PodResolution.Ready -> {
                forwardRecovery.onPodResolved()
                PodForwardResolution(result.pod)
            }
            is PodResolution.Unavailable -> {
                PodForwardResolution(waitForRunningPod())
            }
            is PodResolution.RollDelegated -> {
                forwardRecovery.dismiss()
                thisLogger().info(
                    "Port forward: pod roll to ${podLogIdentity(result.pod)}, " +
                        "re-establishing forward on local port ${sessionCtx.localPort}"
                )
                PodForwardResolution(result.pod)
            }
            is PodResolution.RestartSuppressed -> {
                forwardRecovery.dismiss()
                PodForwardResolution(waitForRunningPod())
            }
        }
    }

    /** Polls until a running pod is available. */
    private suspend fun waitForRunningPod(): V1Pod {
        forwardRecovery.onPodUnavailable()
        thisLogger().info(
            "Port forward: waiting for workspace pod on local port ${sessionCtx.localPort}"
        )
        while (true) {
            delay(POD_POLL_DELAY)
            when (val result = tracker.resolvePod()) {
                is PodResolution.Ready -> {
                    forwardRecovery.onPodResolved()
                    return result.pod
                }
                is PodResolution.RollDelegated -> {
                    forwardRecovery.dismiss()
                    thisLogger().info(
                        "Port forward: pod roll to ${podLogIdentity(result.pod)} during wait, " +
                            "re-establishing forward on local port ${sessionCtx.localPort}"
                    )
                    return result.pod
                }
                is PodResolution.RestartSuppressed -> forwardRecovery.dismiss()
                is PodResolution.Unavailable -> Unit
            }
        }
    }
}
