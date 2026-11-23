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
            !current.isTerminating() && current.isRunning()
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            false
        }
    }

    /**
     * Newest pod that is running, ready and not terminating.
     *
     * The newest qualifying pod is the one the DevWorkspace controller currently serves.
     * A pod without a creation timestamp counts as the oldest, so it never shadows a pod with a
     * known timestamp.
     *
     * @param namespace the namespace to query.
     * @param labelSelector the Kubernetes label selector, for example
     * `controller.devfile.io/devworkspace_name=my-workspace`.
     * @return the newest running and ready pod, or `null` if no pod qualifies.
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    fun findNewestRunning(namespace: String, labelSelector: String): V1Pod? =
        newest(namespace, labelSelector) { pod -> pod.isRunningAndReady() }

    /**
     * Newest pod of [workspaceName] in [namespace] that runs and is not terminating: the pod that
     * serves it, or - with [requireReady] `false` - the one that is about to.
     *
     * Delegates to [newest], so every caller shares one rule for "the serving pod".
     * Blocks on the Kubernetes API: call off the EDT, from a retry or IO loop.
     *
     * @param namespace the workspace's namespace.
     * @param workspaceName the DevWorkspace name, used as the label selector value.
     * @param requireReady `true` for a pod that serves *now*; `false` to also hand out a pod whose
     *   readiness probe has not passed yet, for a caller that polls a starting server instead of
     *   failing on it ([com.redhat.devtools.gateway.server.RemoteIDEServer]).
     * @return the newest qualifying pod, or `null` if no pod qualifies.
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    fun findServingPod(
        namespace: String,
        workspaceName: String,
        requireReady: Boolean = true
    ): V1Pod? =
        newest(namespace, workspaceLabelSelector(workspaceName)) { pod ->
            !pod.isTerminating() && pod.isRunning() && (!requireReady || pod.isReady())
        }

    /** Label selector matching every pod of [workspaceName]. */
    private fun workspaceLabelSelector(workspaceName: String): String =
        "$WORKSPACE_LABEL_KEY=$workspaceName"

    /** Newest of the listed pods that [qualifies]; a pod without a timestamp counts as the oldest. */
    @Throws(ApiException::class)
    private inline fun newest(
        namespace: String,
        labelSelector: String,
        qualifies: (V1Pod) -> Boolean
    ): V1Pod? =
        list(namespace, labelSelector).items
            .filter(qualifies)
            .maxByOrNull { pod -> pod.metadata?.creationTimestamp ?: OffsetDateTime.MIN }

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
