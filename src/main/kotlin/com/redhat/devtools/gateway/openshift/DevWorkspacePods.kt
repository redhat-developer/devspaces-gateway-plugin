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

import com.redhat.devtools.gateway.util.isCancellationException
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import java.time.OffsetDateTime

/**
 * Queries the Kubernetes API for the pods of a DevWorkspace.
 */
class DevWorkspacePods(private val client: ApiClient) {

    companion object {
        const val WORKSPACE_LABEL_KEY = "controller.devfile.io/devworkspace_name"

        private const val POD_PHASE_RUNNING = "Running"
        private const val POD_READY_CONDITION = "Ready"
        private const val CONDITION_STATUS_TRUE = "True"
    }

    /**
     * Checks whether the given pod is still running and not terminating.
     *
     * The pod is queried on each call so that the answer reflects the current cluster state: a pod
     * with a deletion timestamp is terminating and thus not running, even if its phase is still
     * `Running` — the same semantics as the port-forward liveness check.
     *
     * The call is bounded by OkHttp's default 10s connect/read/write timeouts on the shared client,
     * so a hanging API server cannot block the caller indefinitely.
     *
     * @param pod the pod to check.
     * @return `true` if the current phase of the pod is `Running` and it is not terminating,
     * `false` if it is not running, is terminating, or the query fails.
     */
    fun isPodRunning(pod: V1Pod): Boolean {
        val name = pod.metadata?.name ?: return false
        val namespace = pod.metadata?.namespace ?: return false
        return try {
            val current = CoreV1Api(client).readNamespacedPod(name, namespace).execute()
            current.status?.phase == POD_PHASE_RUNNING && current.metadata?.deletionTimestamp == null
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            false
        }
    }

    /**
     * Returns the current usable pod matching the given label selector.
     *
     * A pod roll leaves several pods of the same DevWorkspace behind for a while: the old pod is
     * terminating and the new one is starting. Picking the first list item can therefore pin every
     * later operation to the dying pod, which never serves again. Only a pod that is running, has
     * passed its readiness probe, and is not terminating can serve; among those the newest one is
     * the one the DevWorkspace controller is currently using.
     *
     * A pod without a creation timestamp is treated as the oldest, so it never shadows a pod with
     * a known timestamp.
     *
     * @param namespace the namespace to query.
     * @param labelSelector the Kubernetes label selector, for example
     * `controller.devfile.io/devworkspace_name=my-workspace`.
     * @return the newest running and ready pod, or `null` if no pod qualifies.
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    fun findNewestRunning(namespace: String, labelSelector: String): V1Pod? {
        return list(namespace, labelSelector).items
            .filter(::isRunningAndReady)
            .maxByOrNull { it.metadata?.creationTimestamp ?: OffsetDateTime.MIN }
    }

    private fun isRunningAndReady(pod: V1Pod): Boolean =
        pod.metadata?.deletionTimestamp == null &&
            pod.status?.phase == POD_PHASE_RUNNING &&
            pod.status?.conditions.orEmpty().any { condition ->
                condition.type == POD_READY_CONDITION && condition.status == CONDITION_STATUS_TRUE
            }

    /**
     * Lists the pods matching the given label selector.
     *
     * @param namespace the namespace to query.
     * @param labelSelector the Kubernetes label selector; an empty selector lists all pods in the
     * namespace.
     * @return the matching pods.
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    fun list(namespace: String, labelSelector: String = ""): V1PodList {
        return CoreV1Api(client)
            .listNamespacedPod(namespace)
            .labelSelector(labelSelector)
            .execute()
    }
}
