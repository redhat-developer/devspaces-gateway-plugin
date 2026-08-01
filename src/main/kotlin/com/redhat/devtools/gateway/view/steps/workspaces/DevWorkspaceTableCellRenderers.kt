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

import com.intellij.icons.AllIcons
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.redhat.devtools.gateway.DevSpacesIcons
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceListItem
import java.awt.Component
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer

internal const val ICON_SIZE = 16

private const val TEXT_CELL_LEFT_PADDING = 8

internal abstract class CenteredIconCellRenderer : DefaultTableCellRenderer() {
    protected abstract fun iconFor(item: DevWorkspaceListItem): javax.swing.Icon

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val item = value as DevWorkspaceListItem
        val icon = iconFor(item)
        val label = super.getTableCellRendererComponent(
            table,
            null,
            isSelected,
            false,
            row,
            column
        ) as JLabel
        label.icon = IconUtil.downscaleIconToSize(icon, JBUI.scale(ICON_SIZE), JBUI.scale(ICON_SIZE))
        label.horizontalAlignment = JLabel.CENTER
        label.border = JBUI.Borders.empty()
        return label
    }
}

internal class StatusCellRenderer : CenteredIconCellRenderer() {
    override fun iconFor(item: DevWorkspaceListItem) =
        DevSpacesIcons.getWorkspacePhaseIcon(item.workspace.phase) ?: AllIcons.Empty
}

internal class EditorCellRenderer : CenteredIconCellRenderer() {
    override fun iconFor(item: DevWorkspaceListItem) =
        DevSpacesIcons.getEditorIcon(item.editor.kind)
}

internal class TextCellRenderer(
    private val textProvider: (DevWorkspaceListItem) -> String
) : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val item = value as DevWorkspaceListItem
        val label = super.getTableCellRendererComponent(
            table,
            textProvider(item),
            isSelected,
            false,
            row,
            column
        ) as JLabel
        label.font = JBFont.h4().asPlain()
        label.border = JBUI.Borders.emptyLeft(TEXT_CELL_LEFT_PADDING)
        return label
    }
}