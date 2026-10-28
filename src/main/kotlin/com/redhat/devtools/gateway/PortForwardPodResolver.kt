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
import com.redhat.devtools.gateway.util.podLogIdentity
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/**
 * Adapts [WorkspacePodTracker] outcomes to port-forward transport and recovery progress.
 *
 * While no pod is available, [resolve] polls until a running pod appears before returning.
 * On pod roll, the rolled pod is returned immediately so port-forward can re-establish in
 * parallel with [ThinClientReconnect] (IDE wait via exec, then thin client via local port).
 *
 * The wait is serialized by a mutex so concurrent client connections share one poll loop
 * instead of each hammering the Kubernetes API.
 */
internal class PortForwardPodResolver(
    private val tracker: WorkspacePodTracker,
    private val sessionCtx: ThinClientSessionContext,
    private val forwardRecovery: ForwardRecoveryProgress,
) {

    /** Serializes pod waits: one connection polls, the others wait for its result. */
    private val podWaitMutex = Mutex()

    companion object {
        private val POD_POLL_DELAY = 2.seconds
    }

    suspend fun resolve(): V1Pod =
        resolveAvailablePod() ?: waitForRunningPodShared()

    /**
     * Waits for a running pod, at most one waiter polling at a time. Waiters that queued on
     * the mutex re-check the pod once they acquire it and return immediately when available.
     */
    private suspend fun waitForRunningPodShared(): V1Pod = podWaitMutex.withLock {
        resolveAvailablePod()?.let { return it }
        waitForRunningPod()
    }

    /** Returns the pod when it is already available (ready or rolled), null otherwise. */
    private suspend fun resolveAvailablePod(): V1Pod? =
        when (val result = tracker.resolvePod()) {
            is PodResolution.Ready -> {
                forwardRecovery.onPodResolved()
                result.pod
            }
            is PodResolution.RollDelegated -> {
                forwardRecovery.dismiss()
                thisLogger().info(
                    "Port forward: pod roll to ${podLogIdentity(result.pod)}, " +
                        "re-establishing forward on local port ${sessionCtx.localPort}"
                )
                result.pod
            }
            is PodResolution.RestartSuppressed -> {
                forwardRecovery.dismiss()
                null
            }
            is PodResolution.Unavailable -> null
        }

    /** Polls until a running pod is available. */
    private suspend fun waitForRunningPod(): V1Pod {
        forwardRecovery.onPodUnavailable()
        thisLogger().info(
            "Port forward: waiting for workspace pod on local port ${sessionCtx.localPort}"
        )
        while (true) {
            delay(POD_POLL_DELAY)
            resolveAvailablePod()?.let { return it }
        }
    }
}
