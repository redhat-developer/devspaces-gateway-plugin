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
package com.redhat.devtools.gateway.openshift

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.thisLogger
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CustomObjectsApi
import io.kubernetes.client.util.PatchUtils
import io.kubernetes.client.util.Watch
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


class DevWorkspaces(private val client: ApiClient) {
    companion object {
        const val FAILED: String = "Failed"
        const val RUNNING: String = "Running"
        const val STOPPED: String = "Stopped"
        const val STARTING: String = "Starting"
        const val RUNNING_TIMEOUT: Long = 600
        const val INACTIVITY_TIMEOUT: Long = 150
    }

    @Throws(ApiException::class)
    fun list(namespace: String): List<DevWorkspace> {
        val customApi = CustomObjectsApi(client)
        try {
            val response = customApi.listNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces"
            ).execute()

            val dwItems = Utils.getValue(response, arrayOf("items")) as List<*>
            return dwItems
                .stream()
                .map { dwItem -> DevWorkspace.from(dwItem) }
                .toList()
        } catch (e: ApiException) {
            thisLogger().info(e.message)

            val response = Gson().fromJson(e.responseBody, Map::class.java)
            // There might be some namespaces (OpenShift projects) in which the user cannot list resource "devworkspaces"
            // e.g. "openshift-virtualization-os-images" on Red Hat Dev Sandbox, etc.
            // It doesn't make sense to show an error to the user in such cases,
            // so let's skip it silently.
            if ((response["code"] as Double) == 403.0) {
                return emptyList()
            } else {
                // The error will be shown in the Gateway UI.
                thisLogger().error(e.message)
                throw e
            }
        }
    }

    fun get(namespace: String, name: String): DevWorkspace {
        val customApi = CustomObjectsApi(client)
        val dwObj = customApi.getNamespacedCustomObject(
            "workspace.devfile.io",
            "v1alpha2",
            namespace,
            "devworkspaces",
            name
        ).execute()
        return DevWorkspace.from(dwObj)
    }

    @Throws(ApiException::class)
    fun start(namespace: String, name: String) {
        val patch = arrayOf(mapOf("op" to "replace", "path" to "/spec/started", "value" to true))
        doPatch(namespace, name, patch)
    }

    @Throws(ApiException::class)
    fun stop(namespace: String, name: String) {
        val patch = arrayOf(mapOf("op" to "replace", "path" to "/spec/started", "value" to false))
        doPatch(namespace, name, patch)
    }

    @Throws(ApiException::class, IOException::class)
    fun waitPhase(
        namespace: String,
        name: String,
        desiredPhase: String,
        timeout: Long
    ): Boolean {
        var phaseIsDesiredState = false

        val watcher = createWatcher(namespace, String.format("metadata.name=%s", name))
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.schedule(
            {
                try {
                    for (item in watcher) {
                        val devWorkspace = DevWorkspace.from(item.`object`)
                        if (desiredPhase == devWorkspace.status.phase) {
                            phaseIsDesiredState = true
                            break
                        }
                    }
                } finally {
                    watcher.close()
                    executor.shutdown()
                }
            },
            0,
            TimeUnit.SECONDS
        )

        try {
            executor.awaitTermination(timeout, TimeUnit.SECONDS)
        } finally {
            watcher.close()
            executor.shutdown()
        }

        return phaseIsDesiredState
    }

    // Example:
    // https://github.com/kubernetes-client/java/blob/master/examples/examples-release-20/src/main/java/io/kubernetes/client/examples/PatchExample.java
    @Throws(ApiException::class)
    private fun doPatch(namespace: String, name: String, body: Any) {
        val customApi = CustomObjectsApi(client)
        PatchUtils.patch(
            DevWorkspace.javaClass,
            {
                customApi.patchNamespacedCustomObject(
                    "workspace.devfile.io",
                    "v1alpha2",
                    namespace,
                    "devworkspaces",
                    name,
                    body
                ).buildCall(null)
            },
            V1Patch.PATCH_FORMAT_JSON_PATCH,
            customApi.apiClient
        )
    }

    // Example:
    // https://github.com/kubernetes-client/java/blob/master/examples/examples-release-20/src/main/java/io/kubernetes/client/examples/WatchExample.java
    private fun createWatcher(namespace: String, fieldSelector: String = "", labelSelector: String = ""): Watch<Any> {
        val customObjectsApi = CustomObjectsApi(client)
        return Watch.createWatch(
            client,
            customObjectsApi.listNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces"
            ).fieldSelector(fieldSelector)
                .labelSelector(labelSelector)
                .watch(true)
                .buildCall(null),
            object : TypeToken<Watch.Response<Any>>() {}.type
        )
    }

    @Throws(ApiException::class, IOException::class, CancellationException::class)
    fun waitForPhase(
        namespace: String,
        name: String,
        desiredPhase: String,
        maxWaitTimeSeconds: Long = RUNNING_TIMEOUT,
        maxInactivitySeconds: Long = INACTIVITY_TIMEOUT,
        onProgress: ((phase: String, message: String) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): Boolean {
        var reached = false
        var lastPhase = ""
        var lastMessage = ""
        var lastChangeTime = Instant.now()

        val watcher = createWatcher(namespace, String.format("metadata.name=%s", name))
        val deadline = Instant.now().plusSeconds(maxWaitTimeSeconds)

        try {
            for (event in watcher) {
                if (isCancelled?.invoke() == true) {
                    throw CancellationException("User cancelled the operation")
                }

                val devWorkspace = DevWorkspace.from(event.`object`)
                val currentPhase = devWorkspace.status.phase ?: "Unknown"
                val currentMessage = devWorkspace.status.message ?: ""

                if (currentPhase != lastPhase || currentMessage != lastMessage) {
                    onProgress?.invoke(currentPhase, currentMessage)
                    lastPhase = currentPhase
                    lastMessage = currentMessage
                    lastChangeTime = Instant.now()
                }

                if (currentPhase == desiredPhase) {
                    reached = true
                    break
                }

                if (Duration.between(lastChangeTime, Instant.now()).seconds > maxInactivitySeconds) {
                    onProgress?.invoke(currentPhase, "No progress in $maxInactivitySeconds seconds.")
                    break
                }

                if (Instant.now().isAfter(deadline)) {
                    onProgress?.invoke(currentPhase, "Timed out after $maxWaitTimeSeconds seconds.")
                    break
                }
            }
        } finally {
            watcher.close()
        }

        return reached
    }
}
