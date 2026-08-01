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
package com.redhat.devtools.gateway.view.steps

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.DevSpacesConnection
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceListItem
import com.redhat.devtools.gateway.devworkspace.DevWorkspaces
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceTemplate
import com.redhat.devtools.gateway.devworkspace.WorkspaceEditorKind
import com.redhat.devtools.gateway.openshift.Projects
import com.redhat.devtools.gateway.openshift.Utils
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.server.RemoteIDEServerStatus
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.util.isServerContainerNotFound
import com.redhat.devtools.gateway.util.messageWithoutPrefix
import com.redhat.devtools.gateway.view.steps.workspaces.DevWorkspaceTableModel
import com.redhat.devtools.gateway.view.steps.workspaces.DevWorkspacesTable
import com.redhat.devtools.gateway.view.steps.workspaces.WorkspacesWatch
import com.redhat.devtools.gateway.view.ui.Dialogs
import com.redhat.devtools.gateway.view.ui.Dialogs.confirmUnknownEditor
import com.redhat.devtools.gateway.view.ui.onDoubleClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.awt.Dimension
import java.util.concurrent.CancellationException
import javax.swing.JButton
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener
import javax.swing.event.TableModelEvent
import javax.swing.event.TableModelListener

private const val NO_JETBRAINS_IDE_CONTAINER_MESSAGE =
    "The workspace does not have a JetBrains IDE (idea-server) container, so it cannot be connected to."

private fun WorkspaceEditorKind.isConnectableEditor(): Boolean {
    return when (this) {
        WorkspaceEditorKind.INTELLIJ_IDEA,
        WorkspaceEditorKind.PYCHARM,
        WorkspaceEditorKind.CLION,
        WorkspaceEditorKind.GOLAND,
        WorkspaceEditorKind.PHPSTORM,
        WorkspaceEditorKind.RIDER,
        WorkspaceEditorKind.RUBYMINE,
        WorkspaceEditorKind.WEBSTORM,
        WorkspaceEditorKind.HERDR,
        WorkspaceEditorKind.KIRO,
        WorkspaceEditorKind.JETBRAINS,
        WorkspaceEditorKind.UNKNOWN ->
            true
        else -> false
    }
}

val DevWorkspace.displayName: String
    get() {
        val label = Utils.getValue(this.labels, arrayOf("kubernetes.io/metadata.name")) as String?
        return label ?: this.name
    }

class DevSpacesWorkspacesStepView(
    private var devSpacesContext: DevSpacesContext,
    private val enableNextButton: (() -> Unit)?
) : DevSpacesWizardStep, Disposable {
    override val nextActionText = DevSpacesBundle.message("connector.wizard_step.remote_server_connection.button.next")
    override val previousActionText =
        DevSpacesBundle.message("connector.wizard_step.remote_server_connection.button.previous")

    private var devWorkspacesTableModel = DevWorkspaceTableModel()
    private var devWorkspacesTable = DevWorkspacesTable(devWorkspacesTableModel)

    private lateinit var startDevWorkspaceButton: JButton
    private lateinit var stopDevWorkspaceButton: JButton

    private var watchManager: WorkspacesWatch? = null

    override val component = panel {
        row {
            label(DevSpacesBundle.message("connector.wizard_step.remote_server_connection.title")).applyToComponent {
                font = JBFont.h2().asBold()
            }
        }

        row {
            cell(JBScrollPane(devWorkspacesTable)
                .apply {
                    preferredSize = Dimension(preferredSize.width, 200)
                    minimumSize = Dimension(minimumSize.width, 100)
                })
                .align(AlignX.FILL)
                .align(AlignY.FILL)
        }.resizableRow().bottomGap(BottomGap.MEDIUM)

        row {
            label("").resizableColumn().align(AlignX.FILL)

            startDevWorkspaceButton =
                button(DevSpacesBundle.message("connector.wizard_step.remote_server_connection.button.start")) {
                    startDevWorkspace()
                }.gap(RightGap.SMALL).align(AlignX.RIGHT).component
            stopDevWorkspaceButton =
                button(DevSpacesBundle.message("connector.wizard_step.remote_server_connection.button.stop")) {
                    stopDevWorkspace()
                }.gap(RightGap.SMALL).align(AlignX.RIGHT).component
            button(
                DevSpacesBundle.message("connector.wizard_step.remote_server_connection.button.refresh")
            ) {
                refreshAndWatchAllDevWorkspaces()
            }.gap(RightGap.SMALL).align(AlignX.RIGHT)
        }
    }.apply {
        background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
        border = JBUI.Borders.empty(8)
    }

    override fun onInit() {
        devWorkspacesTableModel.clear() // avoid glitch where user would see old list content before it's cleared
        devWorkspacesTable.selectionModel.addListSelectionListener(DevWorkspaceSelection())
        devWorkspacesTable.onDoubleClick {
            onNext()
        }

        initTableListeners(this)

        watchManager?.dispose()
        watchManager = WorkspacesWatch(devSpacesContext.client, devWorkspacesTableModel)
        refreshAndWatchAllDevWorkspaces()
        enableButtons()
    }

    override fun onPrevious(): Boolean {
        watchManager?.stop()
        return true
    }

    override fun onNext(): Boolean {
        val item = getSelectedWorkspaceListItem() ?: return false
        val workspace = item.workspace
        if (!item.editor.kind.isConnectableEditor()) {
            return false
        }
        if (!isRunning(workspace)) {
            return false
        }
        if (item.editor.kind == WorkspaceEditorKind.UNKNOWN) {
            if (!confirmUnknownEditor()) {
                return false
            }
        }
        devSpacesContext.devWorkspace = workspace
        try {
            getServerStatus()
        } catch (e: Exception) {
            if (e.isCancellationException()) {
                return false // canceled, stay on this step
            }
            thisLogger().error("Could not check workspace IDE status", e)
            if (e.isServerContainerNotFound()) {
                // do not offer restart pod
                Dialogs.error(NO_JETBRAINS_IDE_CONTAINER_MESSAGE, "Cannot Connect to Workspace IDE")
                return false
            }
            if (Dialogs.ideNotResponding()) {
                stopDevWorkspace()
                connect()
            }
            return false
        } ?: return false // Canceled, stay on this step

        connect()
        return false // Stay on this step after connection
    }

    private fun initTableListeners(disposable: Disposable) {
        val selectionListener = ListSelectionListener { enableButtons() }
        devWorkspacesTable.selectionModel.addListSelectionListener(selectionListener)

        val dataListener = object : TableModelListener {
            override fun tableChanged(e: TableModelEvent) = enableButtons()
        }
        devWorkspacesTableModel.addTableModelListener(dataListener)

        Disposer.register(disposable) {
            devWorkspacesTable.selectionModel.removeListSelectionListener(selectionListener)
            devWorkspacesTableModel.removeTableModelListener(dataListener)
        }
    }

    private fun refreshAndWatchAllDevWorkspaces() {
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                watchManager?.stop()
                var lastResourceVersions = emptyMap<String, String?>()
                try {
                    lastResourceVersions = refreshAllDevWorkspaces()
                    enableButtons()
                } catch (e: Exception) {
                    thisLogger().error("Refreshing workspaces failed.", e)
                    Dialogs.error("Could not refresh workspaces: " + e.messageWithoutPrefix(), "Error Refreshing")
                } finally {
                    watchManager?.start(lastResourceVersions)
                }
            },
            DevSpacesBundle.message("connector.loader.devspaces.fetching.text"),
            true,
            null
        )
    }

    private fun refreshAllDevWorkspaces(): Map<String, String?> {
        val lastResourceVersions = mutableMapOf<String, String?>()
        val templateMaps = mutableMapOf<String, Map<String, List<DevWorkspaceTemplate>>>()
        val namespacesUnavailable = mutableSetOf<String>()
        val devWorkspaces = Projects(devSpacesContext.client).list()
            .map { Utils.getValue(it, arrayOf("metadata", "name")) as String }
            .flatMap { namespace ->
                val dwListResult = DevWorkspaces(devSpacesContext.client).listWithResult(namespace)
                lastResourceVersions[namespace] = dwListResult.resourceVersion
                templateMaps[namespace] = dwListResult.templates
                if (dwListResult.templatesUnavailable) {
                    namespacesUnavailable.add(namespace)
                }
                dwListResult.items
            }

        invokeLater(ModalityState.any()) {
            val selectedRow = devWorkspacesTable.selectedRow
            devWorkspacesTableModel.apply {
                clear()
                addAll(devWorkspaces)
            }
            devWorkspacesTable.updateColumnWidths()
            val newSelection = getValidSelectedIndex(selectedRow)
            if (newSelection >= 0) {
                devWorkspacesTable.setRowSelectionInterval(newSelection, newSelection)
            }
        }

        watchManager?.seedTemplateCache(templateMaps, namespacesUnavailable)

        return lastResourceVersions
    }

    private fun getValidSelectedIndex(selectedIndex: Int): Int {
        return if (selectedIndex >= 0
            && selectedIndex < devWorkspacesTableModel.getRowCount()) {
            selectedIndex
        } else {
            if (devWorkspacesTableModel.getRowCount() > 0) 0 else -1
        }
    }

    private fun refreshDevWorkspace(namespace: String, name: String) {
        val refreshedDevWorkspace = DevWorkspaces(devSpacesContext.client).get(namespace, name)
        invokeLater(ModalityState.any()) {
            val idx = devWorkspacesTableModel.indexOfFirst { it.workspace.namespace == namespace && it.workspace.name == name }
            if (idx != -1) {
                // Keep the previously resolved editor: the freshly fetched DevWorkspace has no template
                // context here, so a template-based JetBrains icon must not flip to Unknown (CRW-11897).
                devWorkspacesTableModel.set(
                    idx,
                    DevWorkspaceListItem(
                        refreshedDevWorkspace,
                        devWorkspacesTableModel[idx].editor
                    )
                )
            } else {
                thisLogger().debug(
                    "refreshDevWorkspace: $namespace/$name not in list model; skipping UI update"
                )
            }
        }
    }

    private fun startDevWorkspace() = runWorkspaceAction(
        verb = { namespace, name -> DevWorkspaces(devSpacesContext.client).start(namespace, name) },
        actionError = "Failed to start workspace",
        progressTitle = "Starting Workspace"
    )

    private fun stopDevWorkspace() = runWorkspaceAction(
        verb = { namespace, name -> DevWorkspaces(devSpacesContext.client).stop(namespace, name) },
        actionError = "Failed to stop workspace",
        progressTitle = "Stopping Workspace"
    )

    private fun runWorkspaceAction(
        verb: (String, String) -> Unit,
        actionError: String,
        progressTitle: String
    ) {
        val selectedWorkspace = getSelectedWorkspace() ?: return
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    verb(selectedWorkspace.namespace, selectedWorkspace.name)
                    refreshDevWorkspace(selectedWorkspace.namespace, selectedWorkspace.name)
                    enableButtons()
                } catch (e: Exception) {
                    thisLogger().error(actionError, e)
                    // UI already shows current state, just enable buttons
                    enableButtons()
                }
            },
            progressTitle,
            true,
            null
        )
    }

    private fun getServerStatus(): RemoteIDEServerStatus? {
        var status: RemoteIDEServerStatus? = null
        var errorToThrow: Exception? = null

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    val progressIndicator = ProgressManager.getInstance().progressIndicator
                    progressIndicator.text = "Checking workspace IDE Status..."
                    val checkCancelled = {
                        if (progressIndicator.isCanceled) throw CancellationException()
                    }

                    if (!verifyWorkspaceRunning(checkCancelled)) {
                        return@runProcessWithProgressSynchronously
                    }

                    val remoteIdeServer = RemoteIDEServer(devSpacesContext)
                    status = runBlocking {
                        // Progress text stays visible for the whole wait; update so a long poll
                        // does not look frozen while RemoteIDEServer probes status.
                        progressIndicator.text =
                            "Waiting for workspace IDE to become ready (up to ${RemoteIDEServer.readyTimeout}s)..."
                        remoteIdeServer.waitServerReady(checkCancelled)
                        progressIndicator.text = "Reading workspace IDE status..."
                        remoteIdeServer.getStatus(checkCancelled)
                    }
                } catch (e: Exception) {
                    if (e.isCancellationException()) {
                        return@runProcessWithProgressSynchronously
                    }
                    errorToThrow = e
                }
            },
            "Connect to Workspace IDE",
            true,
            null
        )

        errorToThrow?.let { throw it }
        return status
    }

    private fun verifyWorkspaceRunning(checkCancelled: (() -> Unit)? = null): Boolean {
        return runBlocking {
            DevWorkspaces(devSpacesContext.client).waitPhase(
                devSpacesContext.devWorkspace.namespace,
                devSpacesContext.devWorkspace.name,
                DevWorkspaces.RUNNING,
                DevWorkspaces.RUNNING_TIMEOUT,
                checkCancelled
            )
        }
    }

    private fun connect() {
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    runBlocking(Dispatchers.IO) {
                        DevSpacesConnection(devSpacesContext).connect(
                            { refreshSelectedAndButtons() },
                            { enableButtons() },
                            {
                                if (waitDevWorkspaceStopped(devSpacesContext.devWorkspace)) {
                                    refreshSelectedAndButtons()
                                }
                            }
                        )
                    }
                } catch (e: Exception) {
                    refreshSelectedAndButtons()
                    thisLogger().error("Workspace IDE connection failed.", e)
                    if (e.isServerContainerNotFound()) {
                        Dialogs.error(NO_JETBRAINS_IDE_CONTAINER_MESSAGE, "Cannot Connect to Workspace IDE")
                    } else {
                        Dialogs.error(
                            e.messageWithoutPrefix() ?: "Could not connect to workspace IDE",
                            "Connection Error"
                        )
                    }
                }
            },
            DevSpacesBundle.message("connector.loader.devspaces.connecting.text"),
            true,
            null
        )
    }

    private fun refreshSelectedAndButtons() {
        refreshDevWorkspace(devSpacesContext.devWorkspace.namespace, devSpacesContext.devWorkspace.name)
        enableButtons()
    }

    private fun waitDevWorkspaceStopped(devWorkspace: DevWorkspace): Boolean {
        return runBlocking { DevWorkspaces(devSpacesContext.client)
            .waitPhase(
                devWorkspace.namespace,
                devWorkspace.name,
                DevWorkspaces.STOPPED,
                30
            ) }
    }

    private fun enableButtons() {
        invokeLater(ModalityState.any()) {
            val workspace = getSelectedWorkspace()

            startDevWorkspaceButton.isEnabled = isStopped(workspace)
            stopDevWorkspaceButton.isEnabled = isRunning(workspace)

            refreshNextButton()
        }
    }

    private fun getSelectedWorkspaceListItem(): DevWorkspaceListItem? = devWorkspacesTable.selectedItem

    private fun getSelectedWorkspace(): DevWorkspace? = getSelectedWorkspaceListItem()?.workspace

    override fun isNextEnabled(): Boolean {
        val item = getSelectedWorkspaceListItem() ?: return false
        return item.editor.kind.isConnectableEditor() && isRunning(item.workspace)
    }

    private fun isStopped(workspace: DevWorkspace?): Boolean {
        return workspace?.started == false
    }

    private fun isRunning(workspace: DevWorkspace?): Boolean {
        return workspace?.running ?: false
    }

    fun refreshNextButton() {
        enableNextButton?.invoke()
    }

    override fun dispose() {
        watchManager?.dispose()
        watchManager = null
    }

    inner class DevWorkspaceSelection : ListSelectionListener {
        override fun valueChanged(e: ListSelectionEvent) {
            enableButtons()
            refreshNextButton()
        }
    }
}