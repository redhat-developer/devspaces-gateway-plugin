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

import com.intellij.openapi.diagnostic.thisLogger
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
 * Note:
 * listener callbacks are already invoked on the EDT by [com.redhat.devtools.gateway.devworkspace.DevWorkspaceWatchManager],
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
            val existing = workspacesDataModel[idx].workspace
            if (isStale(dw, existing)) {
                thisLogger().debug(
                    "Ignoring stale update for ${dw.namespace}/${dw.name}: " +
                            "resourceVersion ${dw.resourceVersion} <= ${existing.resourceVersion}"
                )
                return
            }
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

    /**
     * Whether [incoming] is an out-of-order/stale redelivery of [existing] — same
     * workspace, but a resourceVersion that is numerically no newer than what's already
     * displayed (e.g. a relist served from an apiserver replica whose watch cache lags
     * the latest write, see CRW-12992). Fails open (never stale) when either
     * resourceVersion is missing or not a plain integer, so callers that don't populate
     * it behave exactly as before.
     */
    private fun isStale(incoming: DevWorkspace, existing: DevWorkspace): Boolean {
        val incomingVersion = incoming.resourceVersion?.toLongOrNull() ?: return false
        val existingVersion = existing.resourceVersion?.toLongOrNull() ?: return false
        return incomingVersion <= existingVersion
    }

    override fun onDeleted(dw: DevWorkspace) {
        val idx = workspacesDataModel.indexOfFirst { it.workspace == dw }
        if (idx >= 0) {
            workspacesDataModel.remove(idx)
        }
        editorResolver.untrack(dw)
    }

    override fun onReset(namespace: String, items: List<DevWorkspace>) {
        val fresh = items.toSet() // DevWorkspace.equals/hashCode is name+namespace only
        // Remove rows for this namespace no longer present upstream. Iterate
        // highest-index-first so removals don't shift earlier indices.
        for (i in workspacesDataModel.rowCount - 1 downTo 0) {
            val existing = workspacesDataModel[i].workspace
            if (existing.namespace == namespace && existing !in fresh) {
                workspacesDataModel.remove(i)
                editorResolver.untrack(existing)
            }
        }
        // Upsert everything currently reported.
        items.forEach { dw -> onUpdated(dw) }
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