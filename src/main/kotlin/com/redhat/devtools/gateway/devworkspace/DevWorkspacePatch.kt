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
 * * annotation [DevWorkspace.RESTART_KEY]: signals that a restart of the workspace should happen
 * * resource value [DevWorkspace.SPEC_STARTED_PATH]: starts/stops a workspace
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
    }

    /**
     * Checks if the DevWorkspace has the restart annotation set.
     *
     * @return `true` if the restart annotation exists and is set to "true", `false` otherwise.
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    fun hasRestartAnnotation(): Boolean {
        return getDevWorkspace().isAnnotatedForRestart
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
        setAnnotation(DevWorkspace.RESTART_KEY, DevWorkspace.RESTART_VALUE)
    }

    /**
     * Removes the restart annotation from the DevWorkspace resource.
     *
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    fun removeRestartAnnotation() {
        thisLogger().info("Removing restart annotation from $namespace/$name")
        removeAnnotation(DevWorkspace.RESTART_KEY)
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
     * Sets the `started` field at [DevWorkspace.SPEC_STARTED_PATH] on the DevWorkspace.
     *
     * @param value The value to set (true to start, false to stop).
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    internal fun setSpecStarted(value: Boolean) {
        val patch = arrayOf(mapOf(
            "op" to "replace",
            "path" to DevWorkspace.SPEC_STARTED_PATH,
            "value" to value))
        doPatch(patch)
    }

    /**
     * Sets [DevWorkspace.SPEC_TEMPLATE_PATH] to the value of the given template.
     *
     * @param template the template to apply
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    fun setSpecTemplate(template: Map<String, Any?>) {
        val patch = arrayOf(
            mapOf(
                "op" to "replace",
                "path" to DevWorkspace.SPEC_TEMPLATE_PATH,
                "value" to template
            )
        )
        doPatch(patch)
    }

    /**
     * Removes the [DevWorkspace.PENDING_TEMPLATE_KEY] template annotation.
     *
     * Idempotent: the annotation is checked first, so cleanup paths can call this
     * unconditionally without provoking a failing JSON-Patch `remove` (Kubernetes answers
     * 422 when the path does not exist).
     *
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    fun removePendingTemplate() {
        if (!hasPendingTemplate()) {
            thisLogger().debug("No pending template annotation to remove from $namespace/$name")
            return
        }
        thisLogger().info("Removing pending template annotation from $namespace/$name")
        removeAnnotation(DevWorkspace.PENDING_TEMPLATE_KEY)
    }

    /**
     * Checks if the DevWorkspace has the [DevWorkspace.PENDING_TEMPLATE_KEY] annotation set.
     *
     * @return `true` if the pending template annotation is present, `false` otherwise.
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    private fun hasPendingTemplate(): Boolean {
        return getDevWorkspace().annotations
            .containsKey(DevWorkspace.PENDING_TEMPLATE_KEY)
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
