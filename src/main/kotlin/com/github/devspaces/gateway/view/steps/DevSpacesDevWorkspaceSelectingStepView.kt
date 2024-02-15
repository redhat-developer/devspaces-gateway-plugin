/*
 * Copyright (c) 2024 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.github.devspaces.gateway.view.steps

import com.github.devspaces.gateway.DevSpacesBundle
import com.github.devspaces.gateway.openshift.DevWorkspaces
import com.github.devspaces.gateway.DevSpacesContext
import com.github.devspaces.gateway.openshift.Projects
import com.github.devspaces.gateway.openshift.Utils
import com.github.devspaces.gateway.view.Dialog
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.ListCellRenderer

class DevSpacesDevWorkspaceSelectingStepView(private var devSpacesContext: DevSpacesContext) : DevSpacesWizardStep {
    override val nextActionText = DevSpacesBundle.message("connector.wizard_step.devworkspace_selecting.button.next")
    override val previousActionText =
        DevSpacesBundle.message("connector.wizard_step.devworkspace_selecting.button.previous")
    private var listDWDataModel = DefaultListModel<Any>()
    private var listDevWorkspaces = JBList(listDWDataModel)

    override val component = panel {
        row {
            label(DevSpacesBundle.message("connector.wizard_step.devworkspace_selecting.title")).applyToComponent {
                font = JBFont.h2().asBold()
            }
        }
        row {
            cell(JBScrollPane(listDevWorkspaces)).align(AlignX.FILL)
        }
        row {
            button(DevSpacesBundle.message("connector.wizard_step.devworkspace_selecting.button.refresh")) {
                refreshDevWorkspaces()
            }.align(AlignX.RIGHT)
        }
    }.apply {
        background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
        border = JBUI.Borders.empty(8)
    }

    override fun onInit() {
        listDevWorkspaces.cellRenderer = DevWorkspaceListRenderer()
        listDevWorkspaces.setEmptyText(DevSpacesBundle.message("connector.wizard_step.devworkspace_selecting.list.empty_text"))
        refreshDevWorkspaces()
        if (listDWDataModel.size > 0) {
            listDevWorkspaces.selectedIndex = 0
        }
    }

    override fun onPrevious(): Boolean {
        return true
    }

    override fun onNext(): Boolean {
        if (!listDevWorkspaces.isSelectionEmpty) {
            devSpacesContext.devWorkspace = listDWDataModel.get(listDevWorkspaces.selectedIndex)
            return true
        }
        return false
    }

    private fun refreshDevWorkspaces() {
        listDWDataModel.clear()

        try {
            val projects = Projects(devSpacesContext.client).list() as Map<*, *>
            val projectItems = projects["items"] as List<*>

            projectItems.forEach { projectItem ->
                val name = Utils.getValue(projectItem, arrayOf("metadata", "name")) as String

                val devWorkspaces = DevWorkspaces(devSpacesContext.client).list(name) as Map<*, *>
                val devWorkspaceItems = devWorkspaces["items"] as List<*>

                devWorkspaceItems.forEach { devWorkspaceItem ->
                    val phase = Utils.getValue(devWorkspaceItem, arrayOf("status", "phase")) as String
                    if (phase == "Running") {
                        listDWDataModel.addElement(devWorkspaceItem)
                    }
                }
            }
        } catch (e: Exception) {
            val dialog = Dialog(
                "Failed to list DevWorkspaces",
                String.format("Caused: %s", e.toString()),
                component
            )
            dialog.show()
        }
    }

    class DevWorkspaceListRenderer<Any> : ListCellRenderer<Any> {
        override fun getListCellRendererComponent(
            list: JList<out Any>?,
            devWorkspace: Any,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val name = Utils.getValue(devWorkspace, arrayOf("metadata", "name"))
            return JBLabel(name as String)
        }
    }
}