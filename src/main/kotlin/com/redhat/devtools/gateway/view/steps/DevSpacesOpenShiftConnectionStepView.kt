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

import com.google.gson.Gson
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.openshift.OpenShiftClientFactory
import com.redhat.devtools.gateway.openshift.Projects
import com.redhat.devtools.gateway.openshift.kube.KubeConfigBuilder
import com.redhat.devtools.gateway.settings.DevSpacesSettings
import com.redhat.devtools.gateway.view.InformationDialog
import com.redhat.devtools.gateway.view.ui.FilteringComboBox
import com.redhat.devtools.gateway.view.ui.PasteClipboardMenu
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.auth.ApiKeyAuth
import io.kubernetes.client.util.Config
import javax.swing.JComboBox
import javax.swing.JTextField

class DevSpacesOpenShiftConnectionStepView(private var devSpacesContext: DevSpacesContext) : DevSpacesWizardStep {

    private val allServers = KubeConfigBuilder.getServers()
    private var tfToken = JBTextField()
        .apply { PasteClipboardMenu.addTo(this) }
    private var tfServer: JComboBox<String> = FilteringComboBox.create(allServers) { server ->
        tfToken.text = KubeConfigBuilder.getTokenForServer(server) ?: ""
    }.apply { PasteClipboardMenu.addTo(this.editor.editorComponent as JTextField) }

    private var settingsAreLoaded = false
    private val settings = service<DevSpacesSettings>()

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
    }

    override fun onPrevious(): Boolean {
        return true
    }

    override fun onNext(): Boolean {
        val server = tfServer.selectedItem?.toString().orEmpty()
        val token = tfToken.text.toCharArray()

        val client = OpenShiftClientFactory().create(server, token)
        testConnection(client)

        saveOpenShiftConnectionSettings()
        devSpacesContext.client = client
        return true
    }

    private fun testConnection(client: ApiClient) {
        try {
            Projects(client).list()
        } catch (e: Exception) {
            var errorMsg = e.message.orEmpty()
            if (e is ApiException) {
                val response = Gson().fromJson(e.responseBody, Map::class.java)
                errorMsg = String.format("Reason: %s", String.format(response["message"] as String))
            }
            InformationDialog("Connection failed", errorMsg, component).show()
            throw e
        }
    }

    private fun loadOpenShiftConnectionSettings() {
        tfServer.removeAllItems()
        allServers.forEach { tfServer.addItem(it) }

        try {
            val config = Config.defaultClient()
            tfServer.selectedItem = config.basePath
            val auth = config.authentications["BearerToken"]
            if (auth is ApiKeyAuth) tfToken.text = auth.apiKey
        } catch (e: Exception) {
            // Do nothing
        }

        if (tfServer.selectedItem == null || tfToken.text.isEmpty()) {
            tfServer.selectedItem = settings.state.server.orEmpty()
            tfToken.text = settings.state.token.orEmpty()
            settingsAreLoaded = true
        }
    }

    private fun saveOpenShiftConnectionSettings() {
        if (settingsAreLoaded) {
            settings.state.server = tfServer.selectedItem?.toString()
            settings.state.token = tfToken.text
        }
    }
}