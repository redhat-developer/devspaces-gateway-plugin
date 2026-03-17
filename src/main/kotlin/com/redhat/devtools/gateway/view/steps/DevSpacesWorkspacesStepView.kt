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

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.DevSpacesConnection
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.DevSpacesIcons
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceListener
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceWatchManager
import com.redhat.devtools.gateway.devworkspace.DevWorkspaces
import com.redhat.devtools.gateway.openshift.Projects
import com.redhat.devtools.gateway.openshift.Utils
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.server.RemoteIDEServerStatus
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.util.messageWithoutPrefix
import com.redhat.devtools.gateway.view.ui.Dialogs
import com.redhat.devtools.gateway.view.ui.onDoubleClick
import io.kubernetes.client.openapi.ApiClient
import kotlinx.coroutines.runBlocking
import java.awt.Dimension
import java.awt.FontMetrics
import java.util.concurrent.CancellationException
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.ListModel
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener

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

    private var listDWDataModel = DefaultListModel<DevWorkspace>()
    private var listDevWorkspaces = JBList(listDWDataModel)

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
            cell(JBScrollPane(listDevWorkspaces)
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
        listDWDataModel.clear() // avoid glitch where user would see old list content before it's cleared
        listDevWorkspaces.selectionModel.addListSelectionListener(DevWorkspaceSelection())
        listDevWorkspaces.onDoubleClick {
            onNext()
        }
        listDevWorkspaces.cellRenderer = DevWorkspaceListRenderer()
        listDevWorkspaces.setEmptyText(DevSpacesBundle.message("connector.wizard_step.remote_server_connection.list.empty_text"))

        initListListeners(this)

        watchManager = WorkspacesWatch(devSpacesContext.client, listDWDataModel)
        refreshAndWatchAllDevWorkspaces()
    }

    override fun onPrevious(): Boolean {
        watchManager?.stop()
        return true
    }

    override fun onNext(): Boolean {
        val workspace = getSelectedWorkspace() ?: return false
        if (!isRunning(workspace)) {
            return false
        }
        devSpacesContext.devWorkspace = workspace
        val serverStatus = try {
            getServerStatus()
        } catch (e: Exception) {
            if (e.isCancellationException()) {
                return false // canceled, stay on this step
            }
            thisLogger().error("Could not check workspace IDE status", e)
            if (Dialogs.ideNotResponding()) {
                stopDevWorkspace()
                connect()
            }
            return false
        } ?: return false // Canceled, stay on this step

        if (!serverStatus.hasProject) {
            val proceed = MessageDialogBuilder
                .yesNo(
                    "Workspace IDE Without Project",
                    "The Workspace IDE has no project.\nWould you like to connect anyway?"
                )
                .asWarning()
                .yesText("Connect Anyway")
                .noText("Cancel")
                .ask(component)

            if (!proceed) return false // Stay on this step
        }

        connect()
        return false // Stay on this step after connection
    }

    private fun initListListeners(disposable: Disposable) {
        val selectionListener = ListSelectionListener { enableButtons() }
        listDevWorkspaces.addListSelectionListener(selectionListener)

        val dataListener = object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent) = enableButtons()
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent) = enableButtons()
            override fun contentsChanged(e: javax.swing.event.ListDataEvent) = enableButtons()
        }
        listDWDataModel.addListDataListener(dataListener)

        Disposer.register(disposable) {
            listDevWorkspaces.removeListSelectionListener(selectionListener)
            listDWDataModel.removeListDataListener(dataListener)
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
        val devWorkspaces = Projects(devSpacesContext.client).list()
            .map { Utils.getValue(it, arrayOf("metadata", "name")) as String }
            .flatMap { namespace ->
                val dwListResult = DevWorkspaces(devSpacesContext.client).listWithResult(namespace)
                lastResourceVersions[namespace] = dwListResult.resourceVersion
                dwListResult.items
            }

        invokeLater {
            val selectedIndex = listDevWorkspaces.selectedIndex
            listDWDataModel.apply {
                clear()
                addAll(devWorkspaces)
            }
            listDevWorkspaces.selectedIndex = getValidSelectedIndex(selectedIndex)
        }

        return lastResourceVersions
    }

    private fun getValidSelectedIndex(selectedIndex: Int): Int {
        return if (selectedIndex >= 0
            && selectedIndex < listDWDataModel.size) {
            selectedIndex
        } else {
            if (listDWDataModel.size > 0) 0 else -1
        }
    }

    private fun refreshDevWorkspace(namespace: String, name: String) {
        val refreshedDevWorkspace = DevWorkspaces(devSpacesContext.client).get(namespace, name)

        listDWDataModel
            .indexOf(refreshedDevWorkspace)
            .also {
                if (it != -1) listDWDataModel[it] = refreshedDevWorkspace
            }
    }

    private fun startDevWorkspace() {
        val selectedWorkspace = getSelectedWorkspace() ?: return
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    DevWorkspaces(devSpacesContext.client).start(
                        selectedWorkspace.namespace,
                        selectedWorkspace.name
                    )
                    refreshDevWorkspace(
                        selectedWorkspace.namespace,
                        selectedWorkspace.name
                    )
                    enableButtons()
                } catch (e: Exception) {
                    thisLogger().error("Failed to start workspace", e)
                    // UI already shows current state, just enable buttons
                    enableButtons()
                }
            },
            "Starting Workspace",
            true,
            null
        )
    }

    private fun stopDevWorkspace() {
        val selectedWorkspace = getSelectedWorkspace() ?: return
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    DevWorkspaces(devSpacesContext.client).stop(
                        selectedWorkspace.namespace,
                        selectedWorkspace.name
                    )
                    refreshDevWorkspace(
                        selectedWorkspace.namespace,
                        selectedWorkspace.name
                    )
                    enableButtons()
                } catch (e: Exception) {
                    thisLogger().error("Failed to stop workspace", e)
                    // UI already shows current state, just enable buttons
                    enableButtons()
                }
            },
            "Stopping Workspace",
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
                        remoteIdeServer.waitServerReady(checkCancelled)
                        remoteIdeServer.getStatus()
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
                    DevSpacesConnection(devSpacesContext).connect(
                        {
                            refreshDevWorkspace(
                                devSpacesContext.devWorkspace.namespace,
                                devSpacesContext.devWorkspace.name
                            )
                            enableButtons()
                        },
                        {
                            enableButtons()
                        },
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
                    thisLogger().error("Workspace IDE connection failed.", e)
                    Dialogs.error(e.messageWithoutPrefix() ?: "Could not connect to workspace IDE", "Connection Error")
                }
            },
            DevSpacesBundle.message("connector.loader.devspaces.connecting.text"),
            true,
            null
        )
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
        runInEdt {
            val workspace = getSelectedWorkspace()

            startDevWorkspaceButton.isEnabled = isStopped(workspace)
            stopDevWorkspaceButton.isEnabled = isRunning(workspace)

            refreshNextButton()

            if (isAlreadyConnected(workspace)) {
                stopDevWorkspaceButton.toolTipText = "This workspace is already connected."
            } else {
                stopDevWorkspaceButton.toolTipText = null
            }
        }
    }

    private fun getSelectedWorkspace(): DevWorkspace? {
        val selectedIndex = listDevWorkspaces.minSelectionIndex
        return if (selectedIndex >= 0
            && selectedIndex < listDevWorkspaces.itemsCount) {
            listDevWorkspaces.model.getElementAt(selectedIndex)
        } else {
            null
        }
    }

    override fun isNextEnabled(): Boolean {
        val workspace = getSelectedWorkspace() ?: return false
        return isRunning(workspace) && !isAlreadyConnected(workspace)
    }

    private fun isStopped(workspace: DevWorkspace?): Boolean {
        return workspace?.started == false
    }

    private fun isRunning(workspace: DevWorkspace?): Boolean {
        return workspace?.running ?: false
    }

    /**
     * Returns true if the workspace is already connected (client-side session active).
     */
    private fun isAlreadyConnected(workspace: DevWorkspace?): Boolean {
        if (workspace == null) return false
        return devSpacesContext.activeWorkspaces.contains(workspace)
    }

    class DevWorkspaceListRenderer : ColoredListCellRenderer<DevWorkspace>() {
        override fun customizeCellRenderer(
            list: JList<out DevWorkspace>,
            devWorkspace: DevWorkspace,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            val icon = DevSpacesIcons.getWorkspacePhaseIcon(devWorkspace.phase) ?: AllIcons.Empty
            setIcon(icon)

            border = JBUI.Borders.emptyLeft(6)
            font = JBFont.h4().asPlain()

            append(devWorkspace.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (hasMultipleWorkspaces(list.model)) {
                val fm = getFontMetrics(font)
                val maxNameWidth = calculateMaxNameWidth(list.model, fm)
                val padding = maxNameWidth - fm.stringWidth(devWorkspace.name) + 20 // extra gap
                append(" ".repeat(padding / fm.stringWidth(" ")), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(" @${devWorkspace.namespace}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
        }

        private fun calculateMaxNameWidth(listModel: ListModel<out DevWorkspace>, fm: FontMetrics): Int {
            var maxWidth = 0
            for (i in 0 until listModel.size) {
                val nameWidth = fm.stringWidth(listModel.getElementAt(i).name)
                if (nameWidth > maxWidth) maxWidth = nameWidth
            }
            return maxWidth
        }

        private fun hasMultipleWorkspaces(listModel: ListModel<out DevWorkspace>): Boolean {
            if (listModel.size <= 1) return false

            val firstNamespace = listModel.getElementAt(0).namespace
            return (1 until listModel.size)
                .asSequence()
                .map { listModel.getElementAt(it).namespace }
                .any { it != firstNamespace }
        }
    }

    fun refreshNextButton() {
        enableNextButton?.invoke()
    }

    override fun dispose() {
        watchManager?.stop()
    }

    inner class DevWorkspaceSelection : ListSelectionListener {
        override fun valueChanged(e: ListSelectionEvent) {
            enableButtons()
            refreshNextButton()
        }
    }

    private class WorkspacesWatch(
        private val client: ApiClient,
        private val workspacesDataModel: DefaultListModel<DevWorkspace>
    ) {
        private val devWorkspaces = DevWorkspaces(client)
        private val watchManager = DevWorkspaceWatchManager(
            client = client,
            createWatcher = { ns, latestResourceVersion ->
                devWorkspaces.createWatcher(ns, latestResourceVersion = latestResourceVersion)
            },
            createFilter = { ns ->
                devWorkspaces.createIdeaEditorFilter(ns)
            },
            listener = object : DevWorkspaceListener {
                override fun onAdded(dw: DevWorkspace) {
                    onUpdated(dw)
                }

                override fun onUpdated(dw: DevWorkspace) {
                    runInEdt {
                        val idx = indexOfFirst { it.name == dw.name && it.namespace == dw.namespace }
                        if (idx == -1) {
                            val index = findInsertIndex(dw)
                            workspacesDataModel.add(index, dw)
                        } else {
                            workspacesDataModel.set(idx, dw)
                        }
                    }
                }

                override fun onDeleted(dw: DevWorkspace) {
                    runInEdt {
                        val idx = indexOfFirst { it.namespace == dw.namespace && it.name == dw.name }
                        if (idx >= 0) {
                            workspacesDataModel.remove(idx)
                        }
                    }
                }

                private fun findInsertIndex(dw: DevWorkspace): Int {
                    val n = workspacesDataModel.size
                    val groupStart = (0 until n).firstOrNull {
                        workspacesDataModel[it].namespace >= dw.namespace
                    } ?: n

                    val insertIndex = (groupStart until n).firstOrNull {
                        workspacesDataModel[it].namespace == dw.namespace && workspacesDataModel[it].name >= dw.name
                    } ?: run {
                        var endOfGroup = groupStart
                        while (endOfGroup < n && workspacesDataModel[endOfGroup].namespace == dw.namespace) endOfGroup++
                        endOfGroup
                    }

                    return insertIndex
                }
            }
        )

        private fun indexOfFirst(predicate: (DevWorkspace) -> Boolean): Int {
            for (i in 0 until workspacesDataModel.size()) {
                if (predicate(workspacesDataModel.get(i))) return i
            }
            return -1
        }

        fun start(lastResourceVersions: Map<String, String?> = emptyMap()) {
            watchManager.start(lastResourceVersions)
        }

        fun stop() {
            watchManager.stop()
        }
    }
}
