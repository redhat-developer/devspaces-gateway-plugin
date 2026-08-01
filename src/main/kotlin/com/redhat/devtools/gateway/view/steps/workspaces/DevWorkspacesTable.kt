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

import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceListItem
import com.redhat.devtools.gateway.openshift.Utils
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.JTable
import javax.swing.ListSelectionModel

private const val ICON_COLUMN_PADDING = 12
private const val NAME_COLUMN_PADDING = 20
private const val PROJECT_COLUMN_PADDING = 40

internal class DevWorkspacesTable(
    val devWorkspaceModel: DevWorkspaceTableModel = DevWorkspaceTableModel()
) : JBTable(devWorkspaceModel) {

    init {
        autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        tableHeader.reorderingAllowed = false
        setShowVerticalLines(false)
        setShowHorizontalLines(false)
        setCellSelectionEnabled(false)
        setRowSelectionAllowed(true)
        setColumnSelectionAllowed(false)
        rowHeight = JBUI.scale(28)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = DevSpacesBundle.message("connector.wizard_step.remote_server_connection.list.empty_text")
        configureColumns()
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val row = rowAtPoint(event.point)
        val column = columnAtPoint(event.point)
        if (row < 0 || column < 0) return null
        val item = devWorkspaceModel[row]
        return when (column) {
            STATUS_COLUMN -> item.workspace.phase
            EDITOR_COLUMN -> item.editor.tooltip
            else -> null
        }
    }

    val selectedItem: DevWorkspaceListItem?
        get() {
            val row = selectedRow
            return if (row in 0 until devWorkspaceModel.rowCount) devWorkspaceModel[row] else null
        }

    fun updateColumnWidths() {
        val fm = getFontMetrics(JBFont.h4().asPlain())
        var maxNameWidth = 0
        var maxProjectWidth = 0
        for (i in 0 until devWorkspaceModel.rowCount) {
            val item = devWorkspaceModel[i]
            maxNameWidth = maxOf(maxNameWidth, fm.stringWidth(item.workspace.displayName))
            maxProjectWidth = maxOf(maxProjectWidth, fm.stringWidth(item.workspace.namespace))
        }
        columnModel.getColumn(NAME_COLUMN).preferredWidth =
            JBUI.scale(maxNameWidth + NAME_COLUMN_PADDING)
        columnModel.getColumn(PROJECT_COLUMN).preferredWidth =
            JBUI.scale(maxProjectWidth + PROJECT_COLUMN_PADDING)
    }

    private fun configureColumns() {
        columnModel.getColumn(STATUS_COLUMN).cellRenderer = StatusCellRenderer()
        columnModel.getColumn(NAME_COLUMN).cellRenderer =
            TextCellRenderer { it.workspace.displayName }
        columnModel.getColumn(EDITOR_COLUMN).cellRenderer = EditorCellRenderer()
        columnModel.getColumn(PROJECT_COLUMN).cellRenderer =
            TextCellRenderer { it.workspace.namespace }

        val iconColumnWidth = JBUI.scale(ICON_SIZE) + JBUI.scale(ICON_COLUMN_PADDING)
        configureIconColumn(STATUS_COLUMN, iconColumnWidth)
        configureIconColumn(EDITOR_COLUMN, iconColumnWidth)
    }

    private fun configureIconColumn(columnIndex: Int, iconColumnWidth: Int) {
        val column = columnModel.getColumn(columnIndex)
        val headerRenderer = column.headerRenderer ?: tableHeader.defaultRenderer
        val headerComponent = headerRenderer.getTableCellRendererComponent(
            this,
            devWorkspaceModel.getColumnName(columnIndex),
            false,
            false,
            -1,
            columnIndex
        ) as Component
        val width = maxOf(iconColumnWidth, headerComponent.preferredSize.width)
        column.apply {
            preferredWidth = width
            minWidth = width
            maxWidth = width
        }
    }
}

private val com.redhat.devtools.gateway.devworkspace.DevWorkspace.displayName: String
    get() {
        val label = Utils.getValue(this.labels, arrayOf("kubernetes.io/metadata.name")) as String?
        return label
            ?: this.name
    }
