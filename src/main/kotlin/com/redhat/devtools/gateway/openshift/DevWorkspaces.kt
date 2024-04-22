/*
 * Copyright (c) 2024 Red Hat, Inc.
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

import com.google.gson.reflect.TypeToken
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CustomObjectsApi
import io.kubernetes.client.util.Watch
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DevWorkspaces(private val client: ApiClient) {
    companion object {
        val FAILED: String = "Failed"
        val RUNNING: String = "Running"
        val STOPPED: String = "Stopped"
        val STARTING: String = "Starting"
        val RUNNING_TIMEOUT: Long = 300
    }

    @Throws(ApiException::class)
    fun list(namespace: String): List<DevWorkspace> {
        val customApi = CustomObjectsApi(client)
        val response = customApi.listNamespacedCustomObject(
            "workspace.devfile.io",
            "v1alpha2",
            namespace,
            "devworkspaces",
            "false",
            false,
            "",
            "",
            "",
            -1,
            "",
            "",
            -1,
            false
        )

        val dwItems = Utils.getValue(response, arrayOf("items")) as List<*>
        return dwItems
            .stream()
            .map { dwItem -> DevWorkspace.from(dwItem) }
            .toList()
    }

    fun get(namespace: String, name: String): DevWorkspace {
        val customApi = CustomObjectsApi(client)
        val dwObj = customApi.getNamespacedCustomObject(
            "workspace.devfile.io",
            "v1alpha2",
            namespace,
            "devworkspaces",
            name
        )
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

    @Throws(ApiException::class)
    private fun doPatch(namespace: String, name: String, body: Any) {
        val customApi = CustomObjectsApi(client)
        customApi.patchNamespacedCustomObject(
            "workspace.devfile.io",
            "v1alpha2",
            namespace,
            "devworkspaces",
            name,
            body,
            null,
            null,
            null
        )
    }

    // Example:
    // https://github.com/kubernetes-client/java/blob/master/examples/examples-release-18/src/main/java/io/kubernetes/client/examples/WatchExample.java
    private fun createWatcher(namespace: String, fieldSelector: String = "", labelSelector: String = ""): Watch<Any> {
        val customObjectsApi = CustomObjectsApi(client)
        return Watch.createWatch(
            client,
            customObjectsApi.listNamespacedCustomObjectCall(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                "false",
                false,
                "",
                fieldSelector,
                labelSelector,
                -1,
                "",
                "",
                0,
                true,
                null
            ),
            object : TypeToken<Watch.Response<Any>>() {}.type
        )
    }
}
