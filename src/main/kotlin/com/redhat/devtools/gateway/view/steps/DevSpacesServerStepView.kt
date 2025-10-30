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
import com.redhat.devtools.gateway.kubeconfig.KubeconfigFileWatcher
import com.redhat.devtools.gateway.kubeconfig.KubeconfigMonitor
import com.redhat.devtools.gateway.openshift.OpenShiftClientFactory
import com.redhat.devtools.gateway.openshift.Projects
import com.redhat.devtools.gateway.openshift.kube.Cluster
import com.redhat.devtools.gateway.openshift.kube.KubeConfigUtils
import com.redhat.devtools.gateway.settings.DevSpacesSettings
import com.redhat.devtools.gateway.util.message
import com.redhat.devtools.gateway.view.ui.Dialogs
import com.redhat.devtools.gateway.view.ui.FilteringComboBox
import com.redhat.devtools.gateway.view.ui.PasteClipboardMenu
import io.kubernetes.client.openapi.auth.ApiKeyAuth
import io.kubernetes.client.util.Config
import kotlinx.coroutines.*
import javax.swing.JTextField

class DevSpacesServerStepView(
    private var devSpacesContext: DevSpacesContext
) : DevSpacesWizardStep {

    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // KubeConfigBuilder is now an object, so no need to instantiate
    private lateinit var kubeconfigFileWatcher: KubeconfigFileWatcher
    private lateinit var kubeconfigMonitor: KubeconfigMonitor

    private var currentClusters: List<Cluster> = emptyList()

    private var tfToken = JBTextField()
        .apply { PasteClipboardMenu.addTo(this) }
    private var tfServer =
        FilteringComboBox.create(
            emptyList(),
            { it?.name ?: "" },
            { name -> currentClusters.firstOrNull { it.name == name } },
            Cluster::class.java
        ) { cluster ->
            if (cluster != null) {
                tfToken.text = cluster.token ?: ""
            }
        }.apply { PasteClipboardMenu.addTo(this.editor.editorComponent as JTextField) }

    private var settingsAreLoaded = false
    private val settings = service<DevSpacesSettings>()

    /**
     * Checks if both server and token fields have content
     */
    private fun areServerAndTokenValid(): Boolean {
        if (tfServer.selectedItem == null) {
            return false
        }

        return !tfToken.text.isNullOrBlank()
    }

    override fun isNextEnabled(): Boolean {
        return areServerAndTokenValid()
    }

    override val nextActionText = DevSpacesBundle.message("connector.wizard_step.openshift_connection.button.next")
    override val previousActionText =
        DevSpacesBundle.message("connector.wizard_step.openshift_connection.button.previous")
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
    }

    override fun onInit() {
        loadOpenShiftConnectionSettings()
        kubeconfigFileWatcher = KubeconfigFileWatcher(viewScope)
        kubeconfigMonitor = KubeconfigMonitor(viewScope, kubeconfigFileWatcher, KubeConfigUtils)
        kubeconfigMonitor.start()

        viewScope.launch {
            var isInitialLoad = true
            kubeconfigMonitor.onClusterCollected { updatedClusters ->
                withContext(Dispatchers.Main) {
                    updateComboBox(updatedClusters)
                    if (isInitialLoad) {
                        loadOpenShiftConnectionSettings()
                        isInitialLoad = false
                    }
                }
            }
        }
    }

    override fun onPrevious(): Boolean {
        viewScope.cancel()
        return true
    }

    override fun onNext(): Boolean {
        val selectedCluster = tfServer.selectedItem as? Cluster ?: return false
        val server = selectedCluster.apiServerUrl
        val token = tfToken.text
        val client = OpenShiftClientFactory(KubeConfigUtils).create(server, token.toCharArray())
        var success = false

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    Projects(client).list()
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
            saveOpenShiftConnectionSettings()
            devSpacesContext.client = client
        }

        return success
    }

    private fun saveOpenShiftConnectionSettings() {
        if (settingsAreLoaded) {
            val selectedCluster = tfServer.selectedItem as? Cluster
            settings.state.server = selectedCluster?.apiServerUrl.orEmpty()
            settings.state.token = tfToken.text
        }
    }

    private fun updateComboBox(updatedClusters: List<Cluster>) {
        println("DEBUG: updateComboBox called with ${updatedClusters.size} clusters: ${updatedClusters.map { "${it.name}@${it.apiServerUrl}" }}")
        val selectedItem = tfServer.selectedItem as? Cluster
        setComboBoxItems(updatedClusters)
        setComboBoxSelection(updatedClusters, selectedItem)
    }

    private fun setComboBoxItems(updatedClusters: List<Cluster>) {
        tfServer.removeAllItems()
        currentClusters = updatedClusters
        updatedClusters.forEach { tfServer.addItem(it) }
    }

    private fun loadOpenShiftConnectionSettings() {
        tryLoadFromDefaultClient()
        
        if (isSelectionIncomplete()) {
            loadFromSavedSettings()
        }
    }

    private fun setComboBoxSelection(updatedClusters: List<Cluster>, previouslySelectedItem: Cluster?) {
        if (previouslySelectedItem != null) {
            restorePreviousSelection(updatedClusters, previouslySelectedItem)
        } else {
            setDefaultSelection(updatedClusters)
        }
    }

    private fun restorePreviousSelection(updatedClusters: List<Cluster>, previouslySelectedItem: Cluster) {
        val reselectedCluster = updatedClusters.firstOrNull { it.id == previouslySelectedItem.id }
        if (reselectedCluster != null) {
            tfServer.selectedItem = reselectedCluster
        } else {
            tfToken.text = ""
            if (updatedClusters.isNotEmpty()) {
                tfServer.selectedIndex = 0
            } else {
                tfServer.selectedItem = null
            }
        }
    }

    private fun setDefaultSelection(updatedClusters: List<Cluster>) {
        if (updatedClusters.isNotEmpty()) {
            tfServer.selectedIndex = 0
        } else {
            tfServer.selectedItem = null
            tfToken.text = ""
        }
    }

    private fun tryLoadFromDefaultClient() {
        try {
            val config = Config.defaultClient()
            val matchingCluster = currentClusters.find { it.apiServerUrl == config.basePath }
            if (matchingCluster != null) {
                tfServer.selectedItem = matchingCluster
            }
            val auth = config.authentications["BearerToken"]
            if (auth is ApiKeyAuth) tfToken.text = auth.apiKey
        } catch (_: Exception) {
            // Ignore exceptions and continue
        }
    }
    
    private fun isSelectionIncomplete(): Boolean {
        return tfServer.selectedItem == null || tfToken.text.isEmpty()
    }
    
    private fun loadFromSavedSettings() {
        val matchingCluster = currentClusters.find { it.apiServerUrl == settings.state.server.orEmpty() }
        if (matchingCluster != null) {
            tfServer.selectedItem = matchingCluster
        }
        tfToken.text = settings.state.token.orEmpty()
        settingsAreLoaded = true
    }
}