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
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.kubeconfig.FileWatcher
import com.redhat.devtools.gateway.kubeconfig.KubeConfigMonitor
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUpdate
import com.redhat.devtools.gateway.openshift.Cluster
import com.redhat.devtools.gateway.openshift.OpenShiftClientFactory
import com.redhat.devtools.gateway.openshift.Projects
import com.redhat.devtools.gateway.settings.DevSpacesSettings
import com.redhat.devtools.gateway.util.message
import com.redhat.devtools.gateway.view.ui.*
import kotlinx.coroutines.*
import java.awt.event.ItemEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class DevSpacesServerStepView(
    private var devSpacesContext: DevSpacesContext,
    private val enableNextButton: (() -> Unit)?,
    private val triggerNextAction: (() -> Unit)? = null
) : DevSpacesWizardStep {

    private lateinit var allClusters: List<Cluster>

    private val settings: ServerSettings = ServerSettings()

    private lateinit var kubeconfigScope: CoroutineScope
    private lateinit var kubeconfigMonitor: KubeConfigMonitor

    private val updateKubeconfigCheckbox = JBCheckBox("Save configuration")

    private var tfToken = JBTextField()
        .apply {
            document.addDocumentListener(onTokenChanged())
            PasteClipboardMenu.addTo(this)
            addKeyListener(createEnterKeyListener())
        }
    private var tfServer =
        FilteringComboBox.create(
            { it?.toString() ?: "" },
            { Cluster.fromNameAndUrl(it) }
        )
        .apply {
            addItemListener(::onClusterSelected)
            PasteClipboardMenu.addTo(this.editor.editorComponent as JTextField)
            (this.editor.editorComponent as JTextField).addKeyListener(createEnterKeyListener())
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
        row("") {
            cell(updateKubeconfigCheckbox).applyToComponent {
                isOpaque = false
                background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
            }
            enabled(false)
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
                if (allClusters.contains(selectedCluster)) {
                    tfToken.text = selectedCluster.token
                    updateKubeconfigCheckbox.isSelected = false
                }
            }
        }
        enableKubeconfigCheckbox()
    }

    private fun onTokenChanged(): DocumentListener = object : DocumentListener {
        override fun insertUpdate(event: DocumentEvent) {
            enableNextButton?.invoke()
            enableKubeconfigCheckbox()
        }

        override fun removeUpdate(e: DocumentEvent) {
            enableNextButton?.invoke()
            enableKubeconfigCheckbox()
        }

        override fun changedUpdate(e: DocumentEvent?) {
            enableNextButton?.invoke()
            enableKubeconfigCheckbox()
        }
    }

    private fun enableKubeconfigCheckbox() {
        val cluster = tfServer.selectedItem as Cluster?
        val token = tfToken.text
        updateKubeconfigCheckbox.isEnabled =
            !allClusters.contains(cluster)
                    || (cluster?.token ?: "") != token
    }

    private fun createEnterKeyListener(): KeyAdapter {
        return object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && isNextEnabled()) {
                    triggerNextAction?.invoke()
                }
            }
        }
    }

    private fun onClustersChanged(): suspend (List<Cluster>) -> Unit = { updatedClusters ->
        this.allClusters = updatedClusters
        if (updatedClusters.isNotEmpty()) {
            invokeLater {
                val kubeConfigCurrentCluster = KubeConfigUtils.getCurrentClusterName()
                val previouslySelected = tfServer.selectedItem as? Cluster?
                setClusters(updatedClusters)
                setSelectedCluster(
                    (previouslySelected)?.name ?: kubeConfigCurrentCluster,
                    updatedClusters
                )
                enableKubeconfigCheckbox()
            }
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
                    val indicator = ProgressManager.getInstance().progressIndicator
                    saveKubeconfig(tfServer.selectedItem as? Cluster?, tfToken.text, indicator)
                    indicator.text = "Checking connection..."
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

    private fun saveKubeconfig(cluster: Cluster?, token: String?, indicator: ProgressIndicator) {
        if (cluster == null
            || token.isNullOrBlank()
            || !updateKubeconfigCheckbox.isSelected) {
                return
            }

            try {
                indicator.text = "Updating Kube config..."
                KubeConfigUpdate
                    .create(
                        cluster.name.trim(),
                        cluster.url.trim(),
                        token.trim())
                    .apply()
            } catch (e: Exception) {
                Dialogs.error( e.message ?: "Could not update kube config file", "Kubeconfig Update Failed")
            }
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
        this.kubeconfigScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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