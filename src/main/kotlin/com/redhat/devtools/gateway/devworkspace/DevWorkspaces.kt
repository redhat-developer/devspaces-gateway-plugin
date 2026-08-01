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
package com.redhat.devtools.gateway.devworkspace

import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.openshift.Utils
import com.redhat.devtools.gateway.openshift.isDevWorkspaceCrdMissing
import com.redhat.devtools.gateway.openshift.isForbidden
import com.redhat.devtools.gateway.openshift.isNotFound
import com.redhat.devtools.gateway.openshift.isRetryable
import com.redhat.devtools.gateway.openshift.isUnauthorized
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CustomObjectsApi
import io.kubernetes.client.util.Watch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.CancellationException

data class DevWorkspaceListItem(
    val workspace: DevWorkspace,
    val editor: WorkspaceEditorInfo,
)

data class DevWorkspaceListResult(
    val items: List<DevWorkspaceListItem>,
    val resourceVersion: String?,
    val templates: Map<String, List<DevWorkspaceTemplate>> = emptyMap(),
    // True when the template list request failed with 401/403/404 (error ignored), so templates are unavailable.
    // An empty map alone does not imply this.
    val templatesUnavailable: Boolean = false
)

data class Templates(
    val map: Map<String, List<DevWorkspaceTemplate>>,
    val unavailable: Boolean // true when 401/403/404
)

val DevWorkspace.cheEditor: String
    get() {
        return Utils.getValue(this.annotations, arrayOf("che.eclipse.org/che-editor")) as? String ?: "unknown"
    }

class DevWorkspaces(private val client: ApiClient) {
    private val customApi = CustomObjectsApi(client)

    companion object {
        const val FAILED: String = "Failed"
        const val RUNNING: String = "Running"
        const val STOPPED: String = "Stopped"
        const val STARTING: String = "Starting"
        const val STOPPING: String = "Stopping"

        const val RUNNING_TIMEOUT: Long = 300
    }

    @Throws(ApiException::class)
    fun listWithResult(namespace: String): DevWorkspaceListResult {
        val response = try {
            customApi.listNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces"
            ).execute()
        } catch (e: ApiException) {
            if (e.isSkippableNamespaceForDevWorkspaceListing(namespace)) {
                thisLogger().info("Ignored: ${e.message}")
                return DevWorkspaceListResult(emptyList(), null)
            } else {
                thisLogger().error("Kubernetes API error ${e.code}", e)
                throw e
            }
        }

        val templates = loadTemplates(namespace)
        val dwItems = Utils.getValue(response, arrayOf("items")) as List<*>
        val dwList = dwItems
            .map { dwItem -> DevWorkspace.from(dwItem) }
            .map { dw -> DevWorkspaceListItem(dw, WorkspaceEditorInfoProvider.create(dw, templates.map)) }
        val lastResourceVersion = (Utils.getValue(response, arrayOf("metadata", "resourceVersion")) as String?)

        return DevWorkspaceListResult(
            dwList,
            lastResourceVersion,
            templates.map,
            templatesUnavailable = templates.unavailable
        )
    }

    @Throws(ApiException::class)
    fun list(namespace: String): List<DevWorkspace> {
       return listWithResult(namespace).items.map { it.workspace }
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

    /**
     * Loads all DevWorkspaceTemplates for the given namespace and groups them by their owner reference UID.
     *
     * Queries the Kubernetes API for `devworkspacetemplates` resources in the specified namespace,
     * parses each template, and builds a map from owner UID to the list of templates owned by that UID.
     *
     * If the API returns a 401/403/404, returns an empty map with `unavailable = true`.
     *
     * @param namespace the Kubernetes namespace to list templates from
     * @return a [Templates] containing the UID-to-templates map and an availability flag
     */
    fun loadTemplates(namespace: String): Templates {
        try {
            val dwTemplateList = customApi
                .listNamespacedCustomObject(
                    "workspace.devfile.io",
                    "v1alpha2",
                    namespace,
                    "devworkspacetemplates",
                )
                .execute()

            val items = Utils.getValue(dwTemplateList, arrayOf("items")) as? List<*> ?: emptyList<Any>()
            val map = items
                .map { DevWorkspaceTemplate.from(it) }
                .flatMap { templ ->
                    templ.ownerRefencesUids.map { uid -> uid to templ }
                }
                .groupBy(
                    keySelector = { it.first },   // UID
                    valueTransform = { it.second } // DevWorkspaceTemplate
                )
            return Templates(map, unavailable = false)
        } catch (e: ApiException) {
            if (e.isIgnorableTemplateListError()) {
                return Templates(emptyMap(), unavailable = true)
            }
            thisLogger().info(e.message)
            throw e
        }
    }

@Throws(ApiException::class)
    fun start(namespace: String, name: String) {
        DevWorkspacePatch(namespace, name, client) {
            get(namespace, name)
        }.setSpecStarted(true)
    }

    @Throws(ApiException::class)
    fun stop(namespace: String, name: String) {
        DevWorkspacePatch(namespace, name, client) {
            get(namespace, name)
        }.setSpecStarted(false)
    }

    @Throws(ApiException::class)
    fun isRestarting(namespace: String, workspaceName: String): Boolean {
        return DevWorkspacePatch(namespace, workspaceName, client) {
            get(namespace, workspaceName)
        }.hasRestartAnnotation()
    }

    @Throws(ApiException::class)
    fun removeRestarting(namespace: String, workspaceName: String) {
        DevWorkspacePatch(namespace, workspaceName, client) {
            get(namespace, workspaceName)
        }.removeRestartAnnotation()
    }

    @Throws(IOException::class, ApiException::class, CancellationException::class)
    fun startAndWait(
        namespace: String,
        name: String,
        timeoutSec: Long = RUNNING_TIMEOUT,
        checkCancelled: (() -> Unit)? = null
    ) {
        val devWorkspace = get(namespace, name)

        if (!devWorkspace.started) {
            checkCancelled?.invoke()
            start(namespace, name)
        }

        if (!runBlocking { waitPhase(namespace, name, RUNNING, timeoutSec, checkCancelled) }) {
            throw IOException("Workspace '$name' is not running after $timeoutSec seconds")
        }
    }

    @Throws(IOException::class, ApiException::class, CancellationException::class)
    fun stopAndWait(
        namespace: String,
        name: String,
        timeoutSec: Long = RUNNING_TIMEOUT, // seconds
        checkCancelled: (() -> Unit)? = null
    ) {
        val devWorkspace = get(namespace, name)

        if (devWorkspace.started) {
            checkCancelled?.invoke()
            stop(namespace, name)
        }

        if (!runBlocking { waitPhase(namespace, name, STOPPED, timeoutSec, checkCancelled) }) {
            throw IOException("Workspace '$name' has not stopped after $timeoutSec seconds")
        }
    }

    @Suppress("ConvertLongToDuration")
    @Throws(ApiException::class, IOException::class, CancellationException::class)
    suspend fun waitPhase(
        namespace: String,
        name: String,
        desiredPhase: String,
        timeoutSec: Long, // in seconds
        checkCancelled: (() -> Unit)? = null
    ): Boolean {
        return withTimeoutOrNull(timeoutSec * 1000L) {
            while (true) {
                checkCancelled?.invoke()
                val devWorkspace = try {
                    DevWorkspaces(client).get(namespace, name)
                } catch (e: ApiException) {
                    if (e.isRetryable()) {
                        delay(1000L)
                        continue
                    }
                    throw e
                }

                checkCancelled?.invoke()
                when (devWorkspace.phase) {
                    desiredPhase
                        -> return@withTimeoutOrNull true
                    FAILED
                        -> return@withTimeoutOrNull false
                }

                delay(1000L)
            }

            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
    }

    // Waits until the DevWorkspace goes out of any the given phases
    @Throws(ApiException::class, IOException::class, CancellationException::class)
    suspend fun waitPhaseChanges(
        namespace: String,
        name: String,
        currentPhases: Collection<String>,
        timeout: Long, // in seconds
        checkCancelled: (() -> Unit)? = null
    ): Boolean {
        @Suppress("ConvertLongToDuration")
        return withTimeoutOrNull(timeout * 1000L) {
            while (true) {
                checkCancelled?.invoke()

                val devWorkspace = try {
                    DevWorkspaces(client).get(namespace, name)
                } catch (e: Exception) {
                    delay(1000L)
                    continue
                }

                checkCancelled?.invoke()
                if (devWorkspace.phase !in currentPhases) {
                    return@withTimeoutOrNull true // phase changed out of the given set
                }

                delay(1000L)
            }

            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
    }

    // Example:
    // https://github.com/kubernetes-client/java/blob/master/examples/examples-release-20/src/main/java/io/kubernetes/client/examples/WatchExample.java
    fun createWatcher(namespace: String, fieldSelector: String = "", labelSelector: String = "", latestResourceVersion: String? = null): Watch<Any> {
        return Watch.createWatch(
            client,
            customApi.listNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces"
            ).fieldSelector(fieldSelector)
                .labelSelector(labelSelector)
                .resourceVersion(latestResourceVersion)
                .watch(true)
                .buildCall(null),
            object : TypeToken<Watch.Response<Any>>() {}.type
        )
    }

/** Returns `true` if the given exception is ignorable when listing templates.
     * Returns `false` otherwise.
     * Template list failures with 401, 403, or 404 are silently degraded to an empty
     * map with [Templates.unavailable] set to true.
     *
     * Note: 401 is ignorable for templates because templates are optional metadata
     * (editor detection falls back to annotation). However, 401 for devworkspaces
     * listing is NOT ignorable and rethrows — see [isSkippableNamespaceForDevWorkspaceListing].
     */
    private fun ApiException.isIgnorableTemplateListError(): Boolean =
        isUnauthorized() || isForbidden() || isNotFound()

    /** Returns `true` if the given exception is skippable when listing devworkspaces
     * for a specific namespace during multi-namespace scanning.
     * Returns `false` otherwise.
     * Skippable errors: CRD missing (404 with CRD-not-found response body),
     * 403 (Forbidden), or plain 404 — the namespace has no DevSpaces/DevWorkspaces
     * resources or access is denied for system namespaces in multi-namespace scans.
     * Non-skippable: 401 (Unauthorized) propagates/rethrows, and other errors
     * propagate normally.
     */
    private fun ApiException.isSkippableNamespaceForDevWorkspaceListing(namespace: String): Boolean = when {
        isDevWorkspaceCrdMissing() -> true
        isForbidden() -> true
        isNotFound() -> true
        else -> false
    }

}
