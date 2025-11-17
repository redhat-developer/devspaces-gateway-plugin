/*
 * Copyright (c) 2024-2025 Red Hat, Inc.
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

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.kubeconfig.FileWatcher
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import com.redhat.devtools.gateway.kubeconfig.KubeConfigMonitor
import com.redhat.devtools.gateway.openshift.Cluster
import com.redhat.devtools.gateway.openshift.OpenShiftClientFactory
import com.redhat.devtools.gateway.openshift.Projects
import com.redhat.devtools.gateway.settings.DevSpacesSettings
import com.redhat.devtools.gateway.util.message
import com.redhat.devtools.gateway.view.ui.Dialogs
import com.redhat.devtools.gateway.view.ui.FilteringComboBox
import com.redhat.devtools.gateway.view.ui.PasteClipboardMenu
import com.redhat.devtools.gateway.view.ui.getAllElements
import com.redhat.devtools.gateway.view.ui.requestInitialFocus
import kotlinx.coroutines.*
import java.awt.event.ItemEvent
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class DevSpacesServerStepView(
    private var devSpacesContext: DevSpacesContext,
    private val enableNextButton: (() -> Unit)?
) : DevSpacesWizardStep {

    private val settings: ServerSettings = ServerSettings()
    private lateinit var kubeconfigScope: CoroutineScope

    private lateinit var kubeconfigMonitor: KubeConfigMonitor

    private var tfToken = JBTextField()
        .apply {
            document.addDocumentListener(onTokenChanged())
            PasteClipboardMenu.addTo(this)
        }

    private var tfServer =
        FilteringComboBox.create(
            { it?.toString() ?: "" },
            { Cluster.fromUrl(it) }
        )
        .apply {
            addItemListener(::onClusterSelected)
            PasteClipboardMenu.addTo(this.editor.editorComponent as JTextField)
        }

    override val component = panel {
        row {
            label(DevSpacesBundle.message("connector.wizard_step.openshift_connection.title")).applyToComponent {
                font = JBFont.h2().asBold()
            }
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.server")) {
            cell(tfServer).align(Align.FILL)
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.token")) {
            cell(tfToken).align(Align.FILL)
        }
    }.apply {
        background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
        border = JBUI.Borders.empty(8)
        requestInitialFocus(tfServer) // tfServer.focused() does not work
    }

    override val nextActionText = DevSpacesBundle.message("connector.wizard_step.openshift_connection.button.next")
    override val previousActionText =
        DevSpacesBundle.message("connector.wizard_step.openshift_connection.button.previous")

    override fun onInit() {
        startKubeconfigMonitor()
    }

    private fun onClusterSelected(event: ItemEvent) {
        if (event.stateChange == ItemEvent.SELECTED) {
            (event.item as? Cluster)?.let { selectedCluster ->
                val allClusters = tfServer.getAllElements()
                if (allClusters.contains(selectedCluster)) {
                    tfToken.text = selectedCluster.token
                }
            }
        }
    }

    private fun onTokenChanged(): DocumentListener = object : DocumentListener {
        override fun insertUpdate(event: DocumentEvent) {
            enableNextButton?.invoke()
        }

        override fun removeUpdate(e: DocumentEvent) {
            enableNextButton?.invoke()
        }

        override fun changedUpdate(e: DocumentEvent?) {
            enableNextButton?.invoke()
        }
    }

    private fun onClustersChanged(): suspend (List<Cluster>) -> Unit = { updatedClusters ->
        invokeLater {
            val selectedName = (tfServer.selectedItem as? Cluster)?.name
            setClusters(updatedClusters)
            setSelectedCluster(selectedName, updatedClusters)
        }
    }

    override fun onPrevious(): Boolean {
        stopKubeconfigMonitor()
        return true
    }

    override fun onNext(): Boolean {
        val selectedCluster = tfServer.selectedItem as? Cluster ?: return false
        val server = selectedCluster.url
        val token = tfToken.text
        val client = OpenShiftClientFactory(KubeConfigUtils).create(server, token.toCharArray())
        var success = false
        stopKubeconfigMonitor()

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    Projects(client).isAuthenticated()
                    success = true
                } catch (e: Exception) {
                    Dialogs.error(e.message(), "Connection failed")
                    throw e
                }
            },
            "Checking Connection...",
            true,
            null
        )

        if (success) {
            settings.save(tfServer.selectedItem as? Cluster)
            devSpacesContext.client = client
        }

        return success
    }

    override fun isNextEnabled(): Boolean {
        return tfServer.selectedItem != null
                && tfToken.text.isNotEmpty()
    }

    private fun setClusters(clusters: List<Cluster>) {
        this.tfServer.removeAllItems()
        clusters.forEach {
            tfServer.addItem(it)
        }
    }

    private fun setSelectedCluster(name: String?, clusters: List<Cluster>) {
        tfServer.selectedItem = null // Reset selectedItem
        val saved = settings.load(clusters)
        val toSelect = clusters.find { it.name == name }
            ?: clusters.firstOrNull { it.id == saved?.id }
            ?: clusters.firstOrNull()
        tfServer.selectedItem = toSelect
        tfToken.text = toSelect?.token ?: ""
    }

    private fun startKubeconfigMonitor() {
        this.kubeconfigScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        this.kubeconfigMonitor = KubeConfigMonitor(
            kubeconfigScope,
            FileWatcher(kubeconfigScope),
            KubeConfigUtils
        )

        kubeconfigScope.launch {
            kubeconfigMonitor.onClustersCollected(onClustersChanged())
        }
        kubeconfigMonitor.start()
    }

    private fun stopKubeconfigMonitor() {
        kubeconfigMonitor.stop()
        kubeconfigScope.cancel()
    }

    private class ServerSettings {

        val service = service<DevSpacesSettings>()

        fun load(clusters: List<Cluster>): Cluster? {
            return clusters.find { it.url == service.state.server.orEmpty() }
        }

        fun save(toSave: Cluster?) {
            val cluster = toSave ?: return
            service.state.server = cluster.url
        }
    }
}