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
package com.redhat.devtools.gateway.view.steps.workspaces

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceListItem
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceTemplate
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceWatchManager
import com.redhat.devtools.gateway.devworkspace.DevWorkspaces
import com.redhat.devtools.gateway.devworkspace.WorkspaceEditorResolver
import io.kubernetes.client.openapi.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Keeps the [DevWorkspaceTableModel] in sync with the cluster via [DevWorkspaceWatchManager],
 * delegating editor resolution to [WorkspaceEditorResolver] and row updates to [DevWorkspaceTableUpdater].
 */
internal class WorkspacesWatch(
    client: ApiClient,
    private val workspacesTableModel: DevWorkspaceTableModel,
    private val dispatchEdt: (() -> Unit) -> Unit = { block ->
        invokeLater(ModalityState.any(), block)
    }
) {
    private val devWorkspaces = DevWorkspaces(client)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val editorResolver = WorkspaceEditorResolver(
        devWorkspaces = devWorkspaces,
        scope = scope,
            onEditorResolved = { patches ->
                dispatchEdt {
                    for ((dw, editor) in patches) {
                        val idx = workspacesTableModel.indexOfFirst { it.workspace == dw }
                        if (idx != -1) {
                            val current = workspacesTableModel[idx]
                            workspacesTableModel.set(idx, DevWorkspaceListItem(current.workspace, editor))
                        }
                    }
                }
            }
    )

    private val watchManager = DevWorkspaceWatchManager(
        createWatcher = { ns, latestResourceVersion ->
            devWorkspaces.createWatcher(ns, latestResourceVersion = latestResourceVersion)
        },
        createFilter = { _ ->
            { true }
        },
        listener = DevWorkspaceTableUpdater(workspacesTableModel, editorResolver)
    )

    fun seedTemplateCache(
        mapsByNamespace: Map<String, Map<String, List<DevWorkspaceTemplate>>>,
        unavailableNamespaces: Set<String>
    ) {
        editorResolver.seedTemplateCache(mapsByNamespace, unavailableNamespaces)
    }

    fun start(lastResourceVersions: Map<String, String?> = emptyMap()) {
        watchManager.start(lastResourceVersions)
    }

    fun stop() {
        watchManager.stop()
        // Cancel in-flight template fetches for this watch cycle; keep scope for restart.
        editorResolver.cancelInFlight()
    }

    fun dispose() {
        stop()
        scope.cancel()
    }
}