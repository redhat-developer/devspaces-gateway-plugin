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

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves [WorkspaceEditorInfo] for watched DevWorkspaces, caching templates per namespace
 * and coalescing background template fetches (one in-flight fetch per namespace).
 * After a successful fetch, [onEditorResolved] is invoked with the freshly resolved editor
 * of the triggering workspace plus all other workspaces that still resolve to UNKNOWN in
 * that namespace, so coalesced/pending workspaces are not left stale.
 */
internal class WorkspaceEditorResolver(
    private val devWorkspaces: DevWorkspaces,
    private val scope: CoroutineScope,
    private val onEditorResolved: (List<Pair<DevWorkspace, WorkspaceEditorInfo>>) -> Unit,
    private val dispatchEdt: (() -> Unit) -> Unit = { action ->
        invokeLater(ModalityState.any(), action)
    }
) {
    @Volatile
    var templateMapsByNamespace: Map<String, Map<String, List<DevWorkspaceTemplate>>> = emptyMap()
    private val templatesUnavailableNamespaces: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val templateFetchInFlight: ConcurrentHashMap<String, Job> = ConcurrentHashMap()
    private val trackedWorkspaces: ConcurrentHashMap<String, DevWorkspace> = ConcurrentHashMap()

    private fun workspaceKey(dw: DevWorkspace): String = "${dw.namespace}/${dw.name}"

    fun seedTemplateCache(
        mapsByNamespace: Map<String, Map<String, List<DevWorkspaceTemplate>>>,
        unavailableNamespaces: Set<String>
    ) {
        templateMapsByNamespace = mapsByNamespace
        templatesUnavailableNamespaces.clear()
        templatesUnavailableNamespaces.addAll(unavailableNamespaces)
    }

    fun templatesUnavailable(namespace: String): Boolean = namespace in templatesUnavailableNamespaces

    fun resolve(dw: DevWorkspace): WorkspaceEditorInfo {
        trackedWorkspaces[workspaceKey(dw)] = dw
        val templateMap = templateMapsByNamespace[dw.namespace] ?: emptyMap()
        return WorkspaceEditorInfoProvider.create(dw, templateMap)
    }

    fun untrack(dw: DevWorkspace) {
        trackedWorkspaces.remove(workspaceKey(dw))
    }

    fun refreshTracked(dw: DevWorkspace) {
        trackedWorkspaces[workspaceKey(dw)] = dw
    }

    fun backgroundFetchTemplatesAndPatch(dw: DevWorkspace) {
        val ns = dw.namespace
        // Atomic coalesce: only one in-flight fetch per namespace.
        scope.launch {
            val thisJob = coroutineContext[Job]!!
            if (!acquireFetchSlot(ns, thisJob)) {
                return@launch
            }
            try {
                val templates = devWorkspaces.loadTemplates(ns)
                if (templates.unavailable) {
                    templatesUnavailableNamespaces.add(ns)
                    return@launch
                }
                applyTemplates(ns, templates)
                dispatchEdt {
                    onEditorResolved(computePatches(dw, ns, templates.map))
                }
            } finally {
                releaseFetchSlot(ns, thisJob)
            }
        }
    }

    private fun acquireFetchSlot(ns: String, job: Job): Boolean =
        templateFetchInFlight.putIfAbsent(ns, job) == null

    private fun releaseFetchSlot(ns: String, job: Job) {
        templateFetchInFlight.remove(ns, job)
    }

    private fun applyTemplates(ns: String, templates: Templates) {
        templateMapsByNamespace = templateMapsByNamespace + (ns to templates.map)
        templatesUnavailableNamespaces.remove(ns)
    }

    private fun computePatches(
        dw: DevWorkspace,
        ns: String,
        templateMap: Map<String, List<DevWorkspaceTemplate>>
    ): List<Pair<DevWorkspace, WorkspaceEditorInfo>> {
        val patches = mutableListOf<Pair<DevWorkspace, WorkspaceEditorInfo>>()
        val latestDw = trackedWorkspaces[workspaceKey(dw)] ?: dw
        val editorInfo = WorkspaceEditorInfoProvider.create(latestDw, templateMap)
        if (editorInfo.kind != WorkspaceEditorKind.UNKNOWN) {
            patches += latestDw to editorInfo
        }
        val triggerKey = workspaceKey(latestDw)
        patches += trackedWorkspaces.values
            .filter { it.namespace == ns && workspaceKey(it) != triggerKey }
            .map { it to WorkspaceEditorInfoProvider.create(it, templateMap) }
            .filter { (_, editor) -> editor.kind != WorkspaceEditorKind.UNKNOWN }
        return patches
    }

    fun cancelInFlight() {
        templateFetchInFlight.values.forEach { it.cancel() }
        templateFetchInFlight.clear()
    }
}