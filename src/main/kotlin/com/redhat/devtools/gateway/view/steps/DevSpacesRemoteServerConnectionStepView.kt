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
import com.redhat.devtools.gateway.openshift.DevWorkspace
import com.redhat.devtools.gateway.openshift.DevWorkspaces
import com.redhat.devtools.gateway.openshift.Projects
import com.redhat.devtools.gateway.openshift.Utils
import com.redhat.devtools.gateway.util.messageWithoutPrefix
import com.redhat.devtools.gateway.view.InformationDialog
import com.redhat.devtools.gateway.view.ui.Dialogs
import java.awt.Component
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener

class DevSpacesRemoteServerConnectionStepView(private var devSpacesContext: DevSpacesContext) : DevSpacesWizardStep {
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
                enableStopButton()
            }.gap(RightGap.SMALL).align(AlignX.RIGHT)
        }
    }.apply {
        background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
        border = JBUI.Borders.empty(8)
    }

    override fun onInit() {
        listDevWorkspaces.selectionModel.addListSelectionListener(DevWorkspaceSelection())
        listDevWorkspaces.cellRenderer = DevWorkspaceListRenderer()
        listDevWorkspaces.setEmptyText(DevSpacesBundle.message("connector.wizard_step.remote_server_connection.list.empty_text"))
        refreshAllDevWorkspaces()
        enableStopButton()
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
                            it.metadata.namespace,
                            it.metadata.name
                        )
                    ProgressManager.getInstance().runProcessWithProgressSynchronously(
                        {
                            if (waitDevWorkspaceStopped(it)) {
                                refreshDevWorkspace(
                                    it.metadata.namespace,
                                    it.metadata.name
                                )
                                enableStopButton()
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
            InformationDialog(
                "Connection failed",
                String.format(
                    "Already connected to %s",
                    devSpacesContext.devWorkspace.metadata.name
                ),
                component
            ).show()
            return
        }

        listDWDataModel
            .get(listDevWorkspaces.selectedIndex)
            .also {
                devSpacesContext.devWorkspace = it
            }

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    DevSpacesConnection(devSpacesContext).connect(
                        {
                            refreshDevWorkspace(
                                devSpacesContext.devWorkspace.metadata.namespace,
                                devSpacesContext.devWorkspace.metadata.name
                            )
                            enableStopButton()
                        },
                        {},
                        {
                            if (waitDevWorkspaceStopped(devSpacesContext.devWorkspace)) {
                                refreshDevWorkspace(
                                    devSpacesContext.devWorkspace.metadata.namespace,
                                    devSpacesContext.devWorkspace.metadata.name
                                )
                                enableStopButton()
                            }
                        }
                    )
                } catch (e: Exception) {
                    refreshDevWorkspace(
                        devSpacesContext.devWorkspace.metadata.namespace,
                        devSpacesContext.devWorkspace.metadata.name
                    )
                    enableStopButton()
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
                devWorkspace.metadata.namespace,
                devWorkspace.metadata.name,
                DevWorkspaces.STOPPED,
                30
            )
    }

    private fun enableStopButton() {
        stopDevWorkspaceButton.isEnabled =
            !listDevWorkspaces.isSelectionEmpty
                    && listDWDataModel.get(listDevWorkspaces.minSelectionIndex).spec.started
    }

    inner class DevWorkspaceListRenderer : ListCellRenderer<DevWorkspace> {
        override fun getListCellRendererComponent(
            list: JList<out DevWorkspace>?,
            devWorkspace: DevWorkspace,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            return JBLabel(
                String.format(
                    "[%s] %s %s",
                    devWorkspace.status.phase,
                    if (!multipleNamespaces) "" else (devWorkspace.metadata.namespace + " /"),
                    devWorkspace.metadata.name
                )
            ).also {
                it.font = JBFont.h4().asPlain()
            }
        }
    }

    inner class DevWorkspaceSelection : ListSelectionListener {
        override fun valueChanged(e: ListSelectionEvent) {
            enableStopButton()
        }
    }
}
