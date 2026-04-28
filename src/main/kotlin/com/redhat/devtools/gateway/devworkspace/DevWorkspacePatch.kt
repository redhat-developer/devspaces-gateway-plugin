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
package com.redhat.devtools.gateway.devworkspace

import com.intellij.openapi.diagnostic.thisLogger
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CustomObjectsApi
import io.kubernetes.client.util.PatchUtils

/**
 * Patches a specific DevWorkspace resource.
 *
 * * annotation `che.eclipse.org/restart-in-progress`: signals that a restart of the workspace should happen
 * * resource value`spec.started`: starts/stops a workspace
 *
 * @param namespace The namespace of the DevWorkspace.
 * @param name The name of the DevWorkspace.
 * @param customApi The Kubernetes custom objects API client.
 * @param getDevWorkspace Lambda to retrieve the current DevWorkspace resource.
 */
class DevWorkspacePatch(
    private val namespace: String,
    private val name: String,
    private val customApi: CustomObjectsApi,
    private val getDevWorkspace: () -> DevWorkspace
) {
    constructor(namespace: String, name: String, client: ApiClient, getDevWorkspace: () -> DevWorkspace) : this(
        namespace,
        name,
        CustomObjectsApi(client),
        getDevWorkspace
    )

    companion object {
        private const val ANNOTATIONS_PATH = "/metadata/annotations"
        const val RESTART_KEY = "che.eclipse.org/restart-in-progress"
        const val RESTART_VALUE = "true"
    }

    /**
     * Checks if the DevWorkspace has the restart annotation set.
     *
     * @return `true` if the restart annotation exists and is set to "true", `false` otherwise.
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    fun hasRestartAnnotation(): Boolean {
        val devWorkspace = getDevWorkspace()
        return devWorkspace.annotations[RESTART_KEY] == RESTART_VALUE
    }

    /**
     * Adds a restart annotation to the DevWorkspace resource.
     * This signals to the Gateway plugin that a restart is in progress and the
     * workspace should not be stopped when the IDE exits.
     *
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    fun setRestartAnnotation() {
        thisLogger().info("Adding restart annotation to $namespace/$name")
        setAnnotation(RESTART_KEY, RESTART_VALUE)
    }

    /**
     * Removes the restart annotation from the DevWorkspace resource.
     *
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    fun removeRestartAnnotation() {
        thisLogger().info("Removing restart annotation from $namespace/$name")
        removeAnnotation(RESTART_KEY)
    }

    /**
     * Sets an annotation on the DevWorkspace resource.
     *
     * @param key The annotation key.
     * @param value The annotation value.
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    private fun setAnnotation(key: String, value: String) {
        val patch = arrayOf(
            mapOf(
                "op" to "add",
                "path" to "$ANNOTATIONS_PATH/${key.replace("/", "~1")}",
                "value" to value
            )
        )
        doPatch(patch)
    }

    /**
     * Removes an annotation from the DevWorkspace resource.
     *
     * @param key The annotation key.
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    private fun removeAnnotation(key: String) {
        val patch = arrayOf(
            mapOf(
                "op" to "remove",
                "path" to "$ANNOTATIONS_PATH/${key.replace("/", "~1")}"
            )
        )
        doPatch(patch)
    }

    /**
     * Sets the spec.started field on the DevWorkspace.
     * Used by [DevWorkspaces.start] and [DevWorkspaces.stop].
     *
     * @param value The value to set (true to start, false to stop).
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    internal fun setSpecStarted(value: Boolean) {
        val patch = arrayOf(mapOf(
            "op" to "replace",
            "path" to "/spec/started",
            "value" to value))
        doPatch(patch)
    }

    /**
     * Applies a JSON patch to the DevWorkspace resource.
     * This is used internally by all patch operations.
     *
     * @param body The patch body.
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    private fun doPatch(body: Any) {
        PatchUtils.patch(
            DevWorkspace::class.java,
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
}
