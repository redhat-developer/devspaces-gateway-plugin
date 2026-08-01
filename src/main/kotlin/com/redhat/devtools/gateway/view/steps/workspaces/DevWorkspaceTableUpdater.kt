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
package com.redhat.devtools.gateway.view.steps.workspaces

import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceListener
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceListItem
import com.redhat.devtools.gateway.devworkspace.WorkspaceEditorInfo
import com.redhat.devtools.gateway.devworkspace.WorkspaceEditorKind
import com.redhat.devtools.gateway.devworkspace.WorkspaceEditorResolver

/**
 * Applies DevWorkspace watch events to the [DevWorkspaceTableModel], resolving editor info
 * via [WorkspaceEditorResolver] and keeping the model sorted.
 *
 * Note: listener callbacks are already invoked on the EDT by [DevWorkspaceWatchManager],
 * so no additional EDT dispatch is done here.
 */
internal class DevWorkspaceTableUpdater(
    private val workspacesDataModel: DevWorkspaceTableModel,
    private val editorResolver: WorkspaceEditorResolver,
) : DevWorkspaceListener {

    override fun onAdded(dw: DevWorkspace) {
        val resolved = editorResolver.resolve(dw)
        insertOrUpdate(dw, resolved)
        // Namespace negatively cached — no fetch needed; Unknown stays visible.
        if (resolved.kind == WorkspaceEditorKind.UNKNOWN && !editorResolver.templatesUnavailable(dw.namespace)) {
            editorResolver.backgroundFetchTemplatesAndPatch(dw)
        }
    }

    override fun onUpdated(dw: DevWorkspace) {
        val idx = workspacesDataModel.indexOfFirst { it.workspace == dw }
        if (idx != -1) {
            editorResolver.refreshTracked(dw)
            // Phase/status updates do not change the editor. Keep the previously
            // resolved editor info so template-based JetBrains does not flip (CRW-11897).
            val item = DevWorkspaceListItem(dw, workspacesDataModel[idx].editor)
            workspacesDataModel.set(idx, item)
        } else {
            // Missed ADDED (reconnect gap) — resolve like a new workspace.
            onAdded(dw)
        }
    }

    override fun onDeleted(dw: DevWorkspace) {
        val idx = workspacesDataModel.indexOfFirst { it.workspace == dw }
        if (idx >= 0) {
            workspacesDataModel.remove(idx)
        }
        editorResolver.untrack(dw)
    }

    private fun insertOrUpdate(dw: DevWorkspace, editor: WorkspaceEditorInfo) {
        val idx = workspacesDataModel.indexOfFirst { it.workspace == dw }
        val item = DevWorkspaceListItem(dw, editor)
        if (idx == -1) {
            workspacesDataModel.addSorted(item)
        } else {
            workspacesDataModel.set(idx, item)
        }
    }
}