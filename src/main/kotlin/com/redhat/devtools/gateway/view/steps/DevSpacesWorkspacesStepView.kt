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
package com.redhat.devtools.gateway.view.steps

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.DevSpacesConnection
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.DevSpacesIcons
import com.redhat.devtools.gateway.openshift.DevWorkspace
import com.redhat.devtools.gateway.openshift.DevWorkspaces
import com.redhat.devtools.gateway.openshift.Projects
import com.redhat.devtools.gateway.openshift.Utils
import com.redhat.devtools.gateway.util.messageWithoutPrefix
import com.redhat.devtools.gateway.view.ui.Dialogs
import com.redhat.devtools.gateway.view.ui.onDoubleClick
import java.awt.Component
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener

class DevSpacesWorkspacesStepView(
    private var devSpacesContext: DevSpacesContext,
    private val enableNextButton: (() -> Unit)?
) : DevSpacesWizardStep {
    override val nextActionText = DevSpacesBundle.message("connector.wizard_step.remote_server_connection.button.next")
    override val previousActionText =
        DevSpacesBundle.message("connector.wizard_step.remote_server_connection.button.previous")

    private var listDWDataModel = DefaultListModel<DevWorkspace>()
    private var listDevWorkspaces = JBList(listDWDataModel)

    // 'true' when there are DevWorkspaces come from multiple namespaces
    private var multipleNamespaces = false

    private lateinit var stopDevWorkspaceButton: JButton

    override val component = panel {
        row {
            label(DevSpacesBundle.message("connector.wizard_step.remote_server_connection.title")).applyToComponent {
                font = JBFont.h2().asBold()
            }
        }
        row {
            cell(JBScrollPane(listDevWorkspaces)).align(AlignX.FILL)
        }
        row {
            label("").resizableColumn().align(AlignX.FILL)

            stopDevWorkspaceButton =
                button(DevSpacesBundle.message("connector.wizard_step.remote_server_connection.button.stop")) {
                    stopDevWorkspace()
                }.gap(RightGap.SMALL).align(AlignX.RIGHT).component
            button(
                DevSpacesBundle.message("connector.wizard_step.remote_server_connection.button.refresh")
            ) {
                refreshAllDevWorkspaces()
            }.gap(RightGap.SMALL).align(AlignX.RIGHT)
        }
    }.apply {
        background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
        border = JBUI.Borders.empty(8)
    }

    override fun onInit() {
        listDevWorkspaces.selectionModel.addListSelectionListener(DevWorkspaceSelection())
        listDevWorkspaces.onDoubleClick { 
            if (!listDevWorkspaces.isSelectionEmpty) {
                connect()
            }
        }
        listDevWorkspaces.cellRenderer = DevWorkspaceListRenderer()
        listDevWorkspaces.setEmptyText(DevSpacesBundle.message("connector.wizard_step.remote_server_connection.list.empty_text"))
        refreshAllDevWorkspaces()
    }

    override fun onPrevious(): Boolean {
        return true
    }

    override fun onNext(): Boolean {
        if (!listDevWorkspaces.isSelectionEmpty) {
            connect()
        }
        return false
    }

    private fun refreshAllDevWorkspaces() {
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    doRefreshAllDevWorkspaces()
                    enableButtons()
                } catch (e: Exception) {
                    Dialogs.error("Could not refresh workspaces: " + e.messageWithoutPrefix(), "Error Refreshing")
                }
            },
            DevSpacesBundle.message("connector.loader.devspaces.fetching.text"),
            true,
            null
        )
    }

    private fun refreshDevWorkspace(namespace: String, name: String) {
        val refreshedDevWorkspace = DevWorkspaces(devSpacesContext.client).get(namespace, name)

        listDWDataModel
            .indexOf(refreshedDevWorkspace)
            .also {
                if (it != -1) listDWDataModel[it] = refreshedDevWorkspace
            }
    }

    private fun doRefreshAllDevWorkspaces() {
        val devWorkspaces = ArrayList<DevWorkspace>()
        val projects = Projects(devSpacesContext.client).list()

        multipleNamespaces = projects.size > 1

        projects
            .onEach { project ->
                (Utils.getValue(project, arrayOf("metadata", "name")) as String)
                    .also {
                        devWorkspaces.addAll(DevWorkspaces(devSpacesContext.client).list(it))
                    }
            }

        val selectedIndex = listDevWorkspaces.selectedIndex

        listDWDataModel.apply {
            clear()
            devWorkspaces.forEach { dw -> addElement(dw) }
        }

        listDevWorkspaces.selectedIndex = selectedIndex
    }

    private fun stopDevWorkspace() {
        if (!listDevWorkspaces.isSelectionEmpty) {
            listDWDataModel
                .get(listDevWorkspaces.selectedIndex)
                .also {
                    DevWorkspaces(devSpacesContext.client)
                        .stop(
                            it.namespace,
                            it.name
                        )
                    ProgressManager.getInstance().runProcessWithProgressSynchronously(
                        {
                            if (waitDevWorkspaceStopped(it)) {
                                refreshDevWorkspace(
                                    it.namespace,
                                    it.name
                                )
                                enableButtons()
                            }
                        },
                        "Refreshing Workspace",
                        true,
                        null
                    )
                }
        }
    }

    private fun connect() {
        if (devSpacesContext.isConnected) {
            Dialogs.error("Already connected to ${devSpacesContext.devWorkspace.name}", "Connection failed")
            return
        }

        val selectedWorkspace = getSelectedWorkspace()
        if (selectedWorkspace != null) {
            devSpacesContext.devWorkspace = selectedWorkspace
        }

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    DevSpacesConnection(devSpacesContext).connect(
                        {
                            refreshDevWorkspace(
                                devSpacesContext.devWorkspace.namespace,
                                devSpacesContext.devWorkspace.name
                            )
                            enableButtons()
                        },
                        {},
                        {
                            if (waitDevWorkspaceStopped(devSpacesContext.devWorkspace)) {
                                refreshDevWorkspace(
                                    devSpacesContext.devWorkspace.namespace,
                                    devSpacesContext.devWorkspace.name
                                )
                                enableButtons()
                            }
                        }
                    )
                } catch (e: Exception) {
                    refreshDevWorkspace(
                        devSpacesContext.devWorkspace.namespace,
                        devSpacesContext.devWorkspace.name
                    )
                    enableButtons()
                    thisLogger().error("Remote server connection failed.", e)
                    Dialogs.error(e.messageWithoutPrefix() ?: "Could not connect to workspace", "Connection Error")
                }
            },
            DevSpacesBundle.message("connector.loader.devspaces.connecting.text"),
            true,
            null
        )
    }

    private fun waitDevWorkspaceStopped(devWorkspace: DevWorkspace): Boolean {
        return DevWorkspaces(devSpacesContext.client)
            .waitPhase(
                devWorkspace.namespace,
                devWorkspace.name,
                DevWorkspaces.STOPPED,
                30
            )
    }

    private fun enableButtons() {
        runInEdt {
            val selectedWorkspaceStarted = getSelectedWorkspace()?.started ?: false

            stopDevWorkspaceButton.isEnabled = selectedWorkspaceStarted
            refreshNextButton()
        }
    }

    private fun getSelectedWorkspace(): DevWorkspace? {
        return if (listDevWorkspaces.minSelectionIndex >= 0) {
            listDWDataModel.get(listDevWorkspaces.minSelectionIndex)
        } else {
            null
        }
    }

    override fun isNextEnabled(): Boolean {
        return getSelectedWorkspace()?.started ?: false
    }

    inner class DevWorkspaceListRenderer : ListCellRenderer<DevWorkspace> {
        override fun getListCellRendererComponent(
            list: JList<out DevWorkspace>?,
            devWorkspace: DevWorkspace,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val icon = DevSpacesIcons.getWorkspacePhaseIcon(devWorkspace.phase)
            return JBLabel().apply {
                font = JBFont.h4().asPlain()
                border = JBUI.Borders.emptyLeft(6)
                icon?.let { 
                    setIcon(it)
                    horizontalTextPosition = JBLabel.TRAILING
                }
                text = "${if (!multipleNamespaces) "" else (devWorkspace.namespace + " /")} ${devWorkspace.name}"
            }
        }
    }

    fun refreshNextButton() {
        enableNextButton?.invoke()
    }

    inner class DevWorkspaceSelection : ListSelectionListener {
        override fun valueChanged(e: ListSelectionEvent) {
            enableButtons()
            refreshNextButton()
        }
    }
}
