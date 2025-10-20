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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DevWorkspaces(private val client: ApiClient) {
    private val customApi = CustomObjectsApi(client)

    companion object {
        private val CHE_EDITOR_ID_REGEX = Regex("che-.*-server", RegexOption.IGNORE_CASE)

        val FAILED: String = "Failed"
        val RUNNING: String = "Running"
        val STOPPED: String = "Stopped"
        val STARTING: String = "Starting"
        val RUNNING_TIMEOUT: Long = 300
    }

    @Throws(ApiException::class)
    fun list(namespace: String): List<DevWorkspace> {
       try {
            val response = customApi.listNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces"
            ).execute()

            val devWorkspaceTemplateMap = getDevWorkspaceTemplateMap(namespace)
            val dwItems = Utils.getValue(response, arrayOf("items")) as List<*>
            return dwItems
                .stream()
                .map { dwItem -> DevWorkspace.from(dwItem) }
                .filter { isIdeaEditorBased(it, devWorkspaceTemplateMap) }
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

    fun isIdeaEditorBased(devWorkspace: DevWorkspace, devWorkspaceTemplateMap: Map<String, List<DevWorkspaceTemplate>>): Boolean {
        // Quick editor ID check
        val segment = devWorkspace.cheEditor?.split("/")?.getOrNull(1)
        if (segment != null && CHE_EDITOR_ID_REGEX.matches(segment)) {
             return true
        }

        // DevWorkspace Template check
        val templates = devWorkspaceTemplateMap[devWorkspace.uid] ?: return false
        return templates.any { template ->
            @Suppress("UNCHECKED_CAST")
            val components = template.components as? List<Any> ?: return@any false
            components.any { component: Any ->
                val map = component as? Map<*, *> ?: return@any false
                val volume = map["volume"] as? Map<*, *>
                // Check 'volume.name' first (v1alpha1), fallback to top-level 'name' (v1alpha2)
                val name = volume?.get("name") as? String ?: map["name"] as? String
                name.equals("idea-server", ignoreCase = true)
            }
        }
    }

    fun get(namespace: String, name: String): DevWorkspace {
        val dwObj = customApi.getNamespacedCustomObject(
            "workspace.devfile.io",
            "v1alpha2",
            namespace,
            "devworkspaces",
            name
        ).execute()
        return DevWorkspace.from(dwObj)
    }

    // Returns a map of DW Owner UID tp list of DW Templates
    fun getDevWorkspaceTemplateMap(namespace: String): Map<String, List<DevWorkspaceTemplate>> {
        val dwTemplateList = customApi
            .listNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspacetemplates",
            )
            .execute()

        val items = Utils.getValue(dwTemplateList, arrayOf("items")) as? List<*> ?: emptyList<Any>()
        return items
            .map { DevWorkspaceTemplate.from(it) }
            .flatMap { templ ->
                templ.ownerRefencesUids.map { uid -> uid to templ }
            }
            .groupBy(
                keySelector = { it.first },   // UID
                valueTransform = { it.second } // DevWorkspaceTemplate
            )
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

        val watcher = createWatcher(namespace, "metadata.name=$name")
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.schedule(
            {
                try {
                    for (item in watcher) {
                        val devWorkspace = DevWorkspace.from(item.`object`)
                        if (desiredPhase == devWorkspace.phase) {
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
        return Watch.createWatch(
            client,
            customApi.listNamespacedCustomObject(
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
}
