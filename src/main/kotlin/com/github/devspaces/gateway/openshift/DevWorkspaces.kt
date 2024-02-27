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
package com.github.devspaces.gateway.openshift

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CustomObjectsApi
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DevWorkspaces(private val client: ApiClient) {

    private var devWorkspaceStartingTimeout: Long = 300

    @Throws(ApiException::class)
    fun list(namespace: String): Any {
        val customApi = CustomObjectsApi(client)
        return customApi.listNamespacedCustomObject(
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
    }

    fun get(namespace: String, name: String): Any {
        val customApi = CustomObjectsApi(client)
        return customApi.getNamespacedCustomObject(
            "workspace.devfile.io",
            "v1alpha2",
            namespace,
            "devworkspaces",
            name
        )
    }

    @Throws(ApiException::class)
    fun patch(namespace: String, name: String, body: Any) {
        doPatch(namespace, name, body)
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
    fun waitRunning(namespace: String, name: String) {
        val dwPhase = java.util.concurrent.atomic.AtomicReference<String>()
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.scheduleAtFixedRate(
            {
                val devWorkspace = get(namespace, name)
                dwPhase.set(Utils.getValue(devWorkspace, arrayOf("status", "phase")) as String)

                if (dwPhase.get() == "Running" || dwPhase.get() == "Failed") {
                    executor.shutdown()
                }
            }, 0, 5, TimeUnit.SECONDS
        )

        try {
            executor.awaitTermination(devWorkspaceStartingTimeout, TimeUnit.SECONDS)
        } finally {
            executor.shutdown()
        }

        if (dwPhase.get() == "Failed") throw IOException(
            String.format("DevWorkspace '%s' failed to start", name)
        )

        if (dwPhase.get() != "Running") throw IOException(
            String.format(
                "DevWorkspace '%s' is not running after %d seconds",
                name,
                devWorkspaceStartingTimeout
            )
        )
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
}