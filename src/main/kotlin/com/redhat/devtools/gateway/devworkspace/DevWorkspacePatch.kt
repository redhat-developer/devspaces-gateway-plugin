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
import com.redhat.devtools.gateway.openshift.isNotFound
import com.redhat.devtools.gateway.openshift.isUnprocessableEntity
import com.redhat.devtools.gateway.util.isCancellationException
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
@Suppress("TooManyFunctions") // the JSON-Patch operations form one cohesive resource client
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
     * @return the patched workspace, or `null` when the annotation was already absent
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    fun removeRestartAnnotation(): DevWorkspace? {
        thisLogger().info("Removing restart annotation from $namespace/$name")
        return removeAnnotation(DevWorkspace.RESTART_KEY)
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
     * Removes the restart request annotations ([DevWorkspace.RESTART_KEY] and
     * [DevWorkspace.PENDING_TEMPLATE_KEY]) in one patch.
     *
     * The patch is conditional on the resource version read here, so a restart request
     * published concurrently makes the API server reject it instead of deleting the new
     * annotations. A failed JSON-Patch `test` is answered with 422. Removing both in one
     * write also keeps an observer from seeing a state where the restart annotation is gone
     * but the template payload is still there.
     *
     * @return the patched workspace, or `null` when neither annotation was present
     * @throws ApiException if the Kubernetes API call fails, including a rejected conditional
     * patch (422) caused by a concurrently published request
     */
    @Throws(ApiException::class)
    fun removeRestartRequest(): DevWorkspace? {
        val workspace = getDevWorkspace()
        val keys = listOfNotNull(
            DevWorkspace.PENDING_TEMPLATE_KEY
                .takeIf { workspace.annotations.containsKey(it) },
            DevWorkspace.RESTART_KEY
                .takeIf { workspace.annotations.containsKey(it) }
        )
        if (keys.isEmpty()) {
            thisLogger().debug("No restart request annotations to remove from $namespace/$name")
            return null
        }
        thisLogger().info("Removing restart request annotations from $namespace/$name")
        return removeAnnotations(keys, workspace.resourceVersion)
    }

    /**
     * Removes an annotation from the DevWorkspace resource.
     *
     * Idempotent: a `remove` for an annotation that is already gone answers 404/422, which is
     * treated as success so concurrent cleanup paths cannot fail each other. Kubernetes also
     * answers 422 for unrelated rejections, so a 422 only counts as success once the annotation
     * is confirmed absent.
     *
     * @param key The annotation key.
     * @return the patched workspace, or `null` when the annotation was already absent
     * @throws ApiException if the Kubernetes API call fails for another reason, or the
     * annotation is still present after a 422.
     */
    @Throws(ApiException::class)
    private fun removeAnnotation(key: String): DevWorkspace? =
        removeAnnotations(listOf(key), expectedResourceVersion = null)

    /**
     * Removes [keys] in one patch, optionally guarded by a `test` on the given
     * [expectedResourceVersion].
     *
     * @param keys the annotation keys to remove.
     * @param expectedResourceVersion the version the decision to remove is based on, or `null`
     *   for an unconditional removal.
     * @return the patched workspace, or `null` when every key was already absent
     * @throws ApiException if the Kubernetes API call fails for another reason, the resource
     *   version no longer matches, or a key is still present after a 422.
     */
    @Throws(ApiException::class)
    private fun removeAnnotations(
        keys: List<String>,
        expectedResourceVersion: String?
    ): DevWorkspace? {
        val patch = buildList {
            expectedResourceVersion?.let { version ->
                add(mapOf("op" to "test", "path" to "/metadata/resourceVersion", "value" to version))
            }
            keys.forEach { key ->
                add(mapOf("op" to "remove", "path" to "$ANNOTATIONS_PATH/${key.replace("/", "~1")}"))
            }
        }
        try {
            return doPatch(patch)
        } catch (e: ApiException) {
            if (!isAlreadyAbsent(e, keys)) {
                throw e
            }
            keys.forEach { key ->
                thisLogger().debug("Annotation $key is already absent from $namespace/$name")
            }
            return null
        }
    }

    /**
     * Whether the failed `remove` is explained by the annotations being absent. A 404 means the
     * resource itself is gone. A 422 is ambiguous: it reports a missing JSON-Patch path, but also
     * other rejections, so the annotations are verified absent first. If that verification read
     * fails for another reason, the original failure is propagated.
     */
    private fun isAlreadyAbsent(e: ApiException, keys: List<String>): Boolean =
        e.isNotFound()
            || (e.isUnprocessableEntity() && areAbsent(keys))

    @Suppress("TooGenericExceptionCaught") // a failed verification must not mask the original failure
    private fun areAbsent(keys: List<String>): Boolean =
        try {
            val annotations = getDevWorkspace().annotations
            keys.none { annotations.containsKey(it) }
        } catch (e: ApiException) {
            e.isNotFound()
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            thisLogger().debug("Could not verify that the annotations are absent", e)
            false
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
     * Uses `add`, which replaces an existing member and creates a missing one, so the patch does
     * not fail with 422 when the workspace carries no template yet.
     *
     * @param template the template to apply
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    fun setSpecTemplate(template: Map<String, Any?>) {
        val patch = arrayOf(
            mapOf(
                "op" to "add",
                "path" to DevWorkspace.SPEC_TEMPLATE_PATH,
                "value" to template
            )
        )
        doPatch(patch)
    }

    /**
     * Removes the [DevWorkspace.PENDING_TEMPLATE_KEY] template annotation.
     *
     * The presence check reads the workspace first and the removal is conditional on that read's
     * resource version, so a template published concurrently is not deleted.
     *
     * @return the patched workspace, or `null` when there was no pending template to remove
     * @throws ApiException if the Kubernetes API call fails, including a rejected conditional
     * patch (422) caused by a concurrently published request
     */
    @Throws(ApiException::class)
    fun removePendingTemplate(): DevWorkspace? {
        val workspace = getDevWorkspace()
        if (!workspace.annotations.containsKey(DevWorkspace.PENDING_TEMPLATE_KEY)) {
            thisLogger().debug("No pending template annotation to remove from $namespace/$name")
            return null
        }
        thisLogger().info("Removing pending template annotation from $namespace/$name")
        return removeAnnotations(
            listOf(DevWorkspace.PENDING_TEMPLATE_KEY),
            workspace.resourceVersion
        )
    }

    /**
     * Applies a JSON patch to the DevWorkspace resource.
     * This is used internally by all patch operations.
     *
     * @param body The patch body.
     * @return the patched DevWorkspace, as returned by the API server
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    private fun doPatch(body: Any): DevWorkspace? {
        // PatchUtils deserializes the response as the class given here, so the result is a
        // DevWorkspace or an ApiException; `null` only comes from an empty response body.
        return PatchUtils.patch(
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
