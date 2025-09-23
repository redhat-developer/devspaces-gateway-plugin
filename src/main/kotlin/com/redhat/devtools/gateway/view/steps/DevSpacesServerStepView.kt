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
import com.intellij.openapi.ui.ComponentValidator
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
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
import com.redhat.devtools.gateway.openshift.kube.Cluster
import com.redhat.devtools.gateway.openshift.kube.KubeConfigBuilder
import com.redhat.devtools.gateway.settings.DevSpacesSettings
import com.redhat.devtools.gateway.util.message
import com.redhat.devtools.gateway.view.ui.Dialogs
import com.redhat.devtools.gateway.view.ui.FilteringComboBox
import com.redhat.devtools.gateway.view.ui.PasteClipboardMenu
import io.kubernetes.client.openapi.auth.ApiKeyAuth
import io.kubernetes.client.util.Config
import java.net.URI
import java.net.URISyntaxException
import java.util.regex.Pattern
import javax.swing.JComboBox
import javax.swing.JTextField


class DevSpacesServerStepView(private var devSpacesContext: DevSpacesContext) : DevSpacesWizardStep {

    private val allServers = KubeConfigBuilder.getClusters()
    private var tfToken = JBTextField()
        .apply { PasteClipboardMenu.addTo(this) }
    private var tfServer: JComboBox<Cluster> =
        FilteringComboBox.create(
            allServers,
            Cluster::toString,
            Cluster::fromString,
            Cluster::class.java
        ) { cluster ->
            if (cluster != null) {
                val token = KubeConfigBuilder.getTokenForCluster(cluster.name) ?: ""
                tfToken.text = token
            }
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
        createServerValidation()
    }
    
    private fun createServerValidation() {
        val editorComponent = tfServer.editor.editorComponent as JTextField
        
        val validatorDisposable = Disposer.newDisposable()
        
        ComponentValidator(validatorDisposable)
            .withValidator {
                val serverInput = editorComponent.text
                
                // Don't show validation errors for empty input initially
                if (serverInput.isBlank()) {
                    return@withValidator null
                }
                
                val validationMessage = ServerValidator.getValidationMessage(serverInput)
                
                if (validationMessage.isNotEmpty()) {
                    ValidationInfo(validationMessage, tfServer)
                } else {
                    null
                }
            }
            .installOn(tfServer)
            .andRegisterOnDocumentListener(editorComponent)
    }

    override fun isNextEnabled(): Boolean {
        return isServerValid()
                && isTokenValid()
    }

    private fun isServerValid(): Boolean {
        val editorComponent = tfServer.editor.editorComponent as JTextField
        val serverInput = editorComponent.text

        // selected cluster item is always valid
        if (tfServer.selectedItem != null
            && serverInput.isBlank()) {
            return true
        }

        return ServerValidator.isValid(serverInput)
    }

    private fun isTokenValid(): Boolean {
        return !tfToken.text.isNullOrBlank()
    }

    override fun onPrevious(): Boolean {
        return true
    }

    override fun onNext(): Boolean {
        val editorComponent = tfServer.editor.editorComponent as JTextField
        val serverInput = editorComponent.text
        val validationMessage = ServerValidator.getValidationMessage(serverInput)
        
        if (validationMessage.isNotEmpty()) {
            Dialogs.error(validationMessage, "Invalid Server Format")
            return false
        }
        
        val selectedCluster = tfServer.selectedItem as? Cluster ?: return false
        val server = selectedCluster.url
        val token = tfToken.text
        val client = OpenShiftClientFactory().create(server, token.toCharArray())
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

    private fun loadOpenShiftConnectionSettings() {
        tfServer.removeAllItems()
        allServers.forEach { tfServer.addItem(it) }

        try {
            val config = Config.defaultClient()
            val matchingCluster = allServers.find { it.url == config.basePath }
            if (matchingCluster != null) {
                tfServer.selectedItem = matchingCluster
            }
            val auth = config.authentications["BearerToken"]
            if (auth is ApiKeyAuth) tfToken.text = auth.apiKey
        } catch (_: Exception) {
            // Do nothing
        }

        if (tfServer.selectedItem == null || tfToken.text.isEmpty()) {
            val matchingCluster = allServers.find { it.url == settings.state.server.orEmpty() }
            if (matchingCluster != null) {
                tfServer.selectedItem = matchingCluster
            }
            tfToken.text = settings.state.token.orEmpty()
            settingsAreLoaded = true
        }
    }

    private fun saveOpenShiftConnectionSettings() {
        if (settingsAreLoaded) {
            val selectedCluster = tfServer.selectedItem as Cluster
            settings.state.server = selectedCluster.url
            settings.state.token = tfToken.text
        }
    }

    /**
     * Validates server input. Accepts either:
     * 1. Valid URL
     * 2. Cluster name pattern: "<cluster name> (<cluster url>)"
     * 3. Valid cluster name (alphanumeric, dots, hyphens, max 253 chars)
     */
    private object ServerValidator {
        private val CLUSTER_NAME_PATTERN = Pattern.compile("^[a-z0-9.-]+$")
        private const val MAX_CLUSTER_NAME_LENGTH = 253

        fun isValid(input: String?): Boolean {
            if (input.isNullOrBlank()) return false
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return false
            if (isValidUrl(trimmed)) return true
            if (isClusterNameWithUrlPattern(trimmed)) return true
            return isValidClusterName(trimmed)
        }

        fun isValidUrl(input: String): Boolean {
            return try {
                val uri = URI(input)
                uri.scheme != null && (uri.scheme == "http" || uri.scheme == "https")
            } catch (e: URISyntaxException) {
                false
            }
        }

        fun isClusterNameWithUrlPattern(input: String): Boolean {
            val regex = Regex("""^(.+?)\s*\((.+?)\)$""")
            val matchResult = regex.find(input)
            if (matchResult != null) {
                val clusterName = matchResult.groupValues[1].trim()
                val clusterUrl = matchResult.groupValues[2].trim()
                return isValidClusterName(clusterName) && isValidUrl(clusterUrl)
            }
            return false
        }

        fun isValidClusterName(name: String): Boolean {
            if (name.length >= MAX_CLUSTER_NAME_LENGTH) return false
            if (name.startsWith("-") || name.endsWith("-")) return false
            if (name.startsWith(".") || name.endsWith(".")) return false
            return CLUSTER_NAME_PATTERN.matcher(name).matches()
        }

        fun getValidationMessage(input: String?): String {
            if (input.isNullOrBlank()) return "Server field cannot be empty"
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return "Server field cannot be empty"
            if (isValidUrl(trimmed)) return ""
            if (isClusterNameWithUrlPattern(trimmed)) return ""
            if (isValidClusterName(trimmed)) return ""
            return "Invalid server format. Must be a valid URL or cluster name pattern '<cluster name> (<cluster url>)'"
        }
    }

}