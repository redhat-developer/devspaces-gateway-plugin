/*
 * Copyright (c) 2024-2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.openshift

import com.intellij.openapi.diagnostic.logger
import com.redhat.devtools.gateway.util.isCancellationException
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.*

/** Liveness of a DevWorkspace pod. */
internal enum class PodLiveness {
    RUNNING,
    GONE,
    UNKNOWN
}

/**
 * Probes the liveness of a DevWorkspace pod.
 *
 * Distinguishes a pod that will not serve the port-forward anymore ([PodLiveness.GONE]: deleted,
 * terminating, or a terminal phase like Failed/Succeeded) from an uncertain state
 * ([PodLiveness.UNKNOWN]: API failure, or a Pending pod as seen during a recreate race). Only
 * [PodLiveness.GONE] aborts a port-forward; [PodLiveness.UNKNOWN] keeps the retry budget.
 */
internal class PodLivenessProbe(private val client: ApiClient) {

    companion object {
        private const val POD_PHASE_RUNNING = "Running"
        private const val POD_PHASE_PENDING = "Pending"
        private const val POD_PHASE_FAILED = "Failed"
        private const val POD_PHASE_SUCCEEDED = "Succeeded"
        private const val HTTP_STATUS_NOT_FOUND = 404
        private const val POD_LIVENESS_TIMEOUT_MS = 1_000L
    }

    private val logger = logger<PodLivenessProbe>()

    /**
     * Queries the liveness of the given pod, bounded to [POD_LIVENESS_TIMEOUT_MS].
     *
     * Suspending: the HTTP query runs on [Dispatchers.IO] and the `await()` is bounded to
     * [POD_LIVENESS_TIMEOUT_MS] so a hanging API server cannot delay socket closure. Must not be
     * invoked via `runBlocking` from PortForwardDispatcher code — call it from a suspending context
     * (see `decideRetry` in [PodPortForwarder]).
     *
     * After [POD_LIVENESS_TIMEOUT_MS] the caller receives [PodLiveness.UNKNOWN] and returns. The
     * query runs in a scope that is not a child of the caller, so the timeout does not join the
     * blocking HTTP call: it may still finish in the background. [queryLiveness] is a blocking
     * call with no cancellation points, so the best-effort [CoroutineScope.cancel] in the
     * `finally` block cannot interrupt it.
     */
    internal suspend fun query(pod: V1Pod): PodLiveness {
        val name = pod.metadata?.name
        val namespace = pod.metadata?.namespace
        if (name == null || namespace == null) {
            logger.debug("Pod liveness is unknown: pod name or namespace is missing")
            return PodLiveness.UNKNOWN
        }
        // A scope that is NOT a child of the caller, so withTimeoutOrNull can return UNKNOWN after the
        // bound without joining the blocking HTTP call (which may keep running in the background).
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val query = scope.async {
            queryLiveness(name, namespace)
        }
        return try {
            @Suppress("ConvertLongToDuration")
            withTimeoutOrNull(POD_LIVENESS_TIMEOUT_MS) { query.await() }
                ?: PodLiveness.UNKNOWN
        } finally {
            scope.cancel()
        }
    }

    private fun queryLiveness(name: String, namespace: String): PodLiveness =
        try {
            CoreV1Api(client).readNamespacedPod(name, namespace).execute().toLiveness()
        } catch (e: ApiException) {
            if (e.code == HTTP_STATUS_NOT_FOUND) PodLiveness.GONE else e.logUnknownLiveness()
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            e.logUnknownLiveness()
        }

    private fun V1Pod.toLiveness(): PodLiveness {
        if (metadata?.deletionTimestamp != null) return PodLiveness.GONE
        return when (status?.phase) {
            POD_PHASE_RUNNING -> PodLiveness.RUNNING
            // a Pending pod is likely a recreate race on the same name, keep the retry budget
            POD_PHASE_PENDING -> PodLiveness.UNKNOWN
            POD_PHASE_FAILED, POD_PHASE_SUCCEEDED -> PodLiveness.GONE
            else -> PodLiveness.UNKNOWN
        }
    }

    private fun Throwable.logUnknownLiveness(): PodLiveness {
        logger.debug("Pod liveness is unknown", this)
        return PodLiveness.UNKNOWN
    }
}
