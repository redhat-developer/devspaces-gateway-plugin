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

import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceListItem
import com.redhat.devtools.gateway.devworkspace.WorkspaceEditorKind
import javax.swing.table.AbstractTableModel

internal const val STATUS_COLUMN = 0
internal const val NAME_COLUMN = 1
internal const val EDITOR_COLUMN = 2
internal const val PROJECT_COLUMN = 3

private fun editorPriority(kind: WorkspaceEditorKind): Int {
    return when (kind) {
        WorkspaceEditorKind.INTELLIJ_IDEA, WorkspaceEditorKind.PYCHARM, WorkspaceEditorKind.CLION,
        WorkspaceEditorKind.GOLAND, WorkspaceEditorKind.PHPSTORM, WorkspaceEditorKind.RIDER,
        WorkspaceEditorKind.RUBYMINE, WorkspaceEditorKind.WEBSTORM, WorkspaceEditorKind.HERDR,
        WorkspaceEditorKind.KIRO, WorkspaceEditorKind.JETBRAINS -> 0
        WorkspaceEditorKind.UNKNOWN -> 1
        else -> 2
    }
}

private val DEV_WORKSPACE_COMPARATOR = Comparator<DevWorkspaceListItem> { a, b ->
    val byEditor = editorPriority(a.editor.kind).compareTo(editorPriority(b.editor.kind))
    if (byEditor != 0) return@Comparator byEditor
    val byNamespace = a.workspace.namespace.compareTo(b.workspace.namespace)
    if (byNamespace != 0) return@Comparator byNamespace
    a.workspace.name.compareTo(b.workspace.name)
}

internal class DevWorkspaceTableModel : AbstractTableModel() {
    private val items = ArrayList<DevWorkspaceListItem>()

    override fun getRowCount(): Int = items.size

    override fun getColumnCount(): Int = 4

    override fun getColumnName(column: Int): String {
        return when (column) {
            STATUS_COLUMN -> DevSpacesBundle.message("connector.wizard_step.remote_server_connection.column.status")
            NAME_COLUMN -> DevSpacesBundle.message("connector.wizard_step.remote_server_connection.column.name")
            EDITOR_COLUMN -> DevSpacesBundle.message("connector.wizard_step.remote_server_connection.column.editor")
            PROJECT_COLUMN -> DevSpacesBundle.message("connector.wizard_step.remote_server_connection.column.project")
            else -> ""
        }
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = items[rowIndex]

    operator fun get(index: Int): DevWorkspaceListItem = items[index]

    fun indexOfFirst(predicate: (DevWorkspaceListItem) -> Boolean): Int {
        for (i in items.indices) {
            if (predicate(items[i])) return i
        }
        return -1
    }

    fun clear() {
        val size = items.size
        items.clear()
        if (size > 0) fireTableRowsDeleted(0, size - 1)
    }

    fun addAll(newItems: List<DevWorkspaceListItem>) {
        val start = items.size
        items.addAll(newItems.sortedWith(DEV_WORKSPACE_COMPARATOR))
        if (newItems.isNotEmpty()) fireTableRowsInserted(start, items.size - 1)
    }

    fun addSorted(item: DevWorkspaceListItem) {
        var low = 0
        var high = items.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (DEV_WORKSPACE_COMPARATOR.compare(item, items[mid]) < 0) {
                high = mid
            } else {
                low = mid + 1
            }
        }
        add(low, item)
    }

    fun add(index: Int, item: DevWorkspaceListItem) {
        items.add(index, item)
        fireTableRowsInserted(index, index)
    }

    fun set(index: Int, item: DevWorkspaceListItem) {
        val old = items[index]
        if (DEV_WORKSPACE_COMPARATOR.compare(old, item) != 0) {
            // Sort key changed (e.g. editor resolved from Unknown) — reposition the row.
            items.removeAt(index)
            fireTableRowsDeleted(index, index)
            addSorted(item)
        } else {
            items[index] = item
            fireTableRowsUpdated(index, index)
        }
    }

    fun remove(index: Int) {
        items.removeAt(index)
        fireTableRowsDeleted(index, index)
    }
}