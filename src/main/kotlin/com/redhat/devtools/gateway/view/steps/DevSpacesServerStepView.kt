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

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
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
import com.redhat.devtools.gateway.auth.sandbox.SandboxClusterAuthProvider
import com.redhat.devtools.gateway.auth.code.AuthTokenKind
import com.redhat.devtools.gateway.auth.code.TokenModel
import com.redhat.devtools.gateway.auth.session.RedHatAuthSessionManager
import com.redhat.devtools.gateway.kubeconfig.FileWatcher
import com.redhat.devtools.gateway.kubeconfig.KubeConfigMonitor
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUpdate
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import com.redhat.devtools.gateway.openshift.Cluster
import com.redhat.devtools.gateway.openshift.OpenShiftClientFactory
import com.redhat.devtools.gateway.openshift.Projects
import com.redhat.devtools.gateway.settings.DevSpacesSettings
import com.redhat.devtools.gateway.view.ui.Dialogs
import com.redhat.devtools.gateway.view.ui.FilteringComboBox
import com.redhat.devtools.gateway.view.ui.PasteClipboardMenu
import com.redhat.devtools.gateway.view.ui.requestInitialFocus
import kotlinx.coroutines.*
import java.awt.event.ItemEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import com.redhat.devtools.gateway.auth.session.LOGIN_TIMEOUT_MS
import com.redhat.devtools.gateway.auth.session.OpenShiftAuthSessionManager
import io.kubernetes.client.openapi.ApiClient

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

    private val sessionManager =
        ApplicationManager.getApplication()
            .getService(RedHatAuthSessionManager::class.java)

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

     private enum class AuthMethod {
        TOKEN,
        OPENSHIFT,
        SSO
    }

    private var authMethod: AuthMethod = AuthMethod.TOKEN

    private fun updateAuthUiState() {
        tfToken.isEnabled = authMethod == AuthMethod.TOKEN
        enableNextButton?.invoke()
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

        buttonsGroup {
            row("Authentication") {
                radioButton("Token")
                    .applyToComponent {
                        isSelected = true
                        toolTipText = "Use a manually provided token from kubeconfig or oc login"
                        addActionListener {
                            authMethod = AuthMethod.TOKEN
                            updateAuthUiState()
                        }
                    }

                radioButton("OpenShift OAuth")
                    .applyToComponent {
                        addActionListener {
                            toolTipText = "Authenticate via OpenShift Authenticator (oc login --web)"
                            authMethod = AuthMethod.OPENSHIFT
                            updateAuthUiState()
                        }
                    }

                radioButton("Red Hat SSO (Sandbox)")
                    .applyToComponent {
                        addActionListener {
                            toolTipText = "Authenticate via Red Hat SSO token (Sandbox only)"
                            authMethod = AuthMethod.SSO
                            updateAuthUiState()
                        }
                    }
            }
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
        updateAuthUiState()
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
        var success = false

        stopKubeconfigMonitor()

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                val indicator = ProgressManager.getInstance().progressIndicator

                try {
                    indicator.text = "Connecting to cluster..."

                    when (authMethod) {
                        AuthMethod.TOKEN -> {
                            indicator.text = "Validating token..."

                            val token = tfToken.text

                            val client = createValidatedApiClient(server, token,
                                "Authentication failed: invalid server URL or token.")

                            saveKubeconfig(selectedCluster, token, indicator)
                            devSpacesContext.client = client
                        }

                        AuthMethod.OPENSHIFT -> {
                            indicator.text = "Authenticating with Openshift..."

                            val finalToken = runBlocking {
                                val openshiftSSessionManager = OpenShiftAuthSessionManager()
                                val uri = openshiftSSessionManager.startLogin(selectedCluster.url)
                                BrowserUtil.browse(uri)

                                indicator.text = "Waiting for you to complete login in your browser..."
                                indicator.checkCanceled()

                                indicator.text = "Obtaining OpenShift access..."
                                val osToken = openshiftSSessionManager.awaitLoginResult(LOGIN_TIMEOUT_MS)

                                TokenModel(
                                    accessToken = osToken.accessToken,
                                    expiresAt = osToken.expiresAt,
                                    accountLabel = osToken.accountLabel,
                                    kind = AuthTokenKind.TOKEN,
                                    clusterApiUrl = selectedCluster.url
                                )
                            }

                            indicator.text = "Validating cluster access..."

                            val client = createValidatedApiClient(server, finalToken.accessToken,
                                "Authentication failed: token received from OpenShift Authenticator is invalid or expired.")

                            tfToken.text = finalToken.accessToken
                            saveKubeconfig(selectedCluster, finalToken.accessToken, indicator)
                            devSpacesContext.client = client
                        }

                        AuthMethod.SSO -> {
                            indicator.text = "Authenticating with Red Hat..."

                            val finalToken = runBlocking {
                                val uri = sessionManager.startLogin()
                                BrowserUtil.browse(uri)

                                indicator.text = "Waiting for you to complete login in your browser..."
                                indicator.checkCanceled()

                                val ssoToken = sessionManager.awaitLoginResult(LOGIN_TIMEOUT_MS)
                                indicator.text = "Obtaining OpenShift access..."

                                val sandboxAuth = SandboxClusterAuthProvider()
                                sandboxAuth.authenticate(ssoToken)
                            }

                            indicator.text = "Validating cluster access..."

                            val client = createValidatedApiClient(server, finalToken.accessToken,
                                "Authentication failed: Red Hat SSO token is invalid or unauthorized for this cluster.")

                            // Do not save SSO tokens
                            if (finalToken.kind == AuthTokenKind.PIPELINE) {
                                saveKubeconfig(selectedCluster, finalToken.accessToken, indicator)
                            }
                            devSpacesContext.client = client
                        }
                    }

                    success = true
                } catch (e: Exception) {
                    Dialogs.error(
                        e.message ?: "Unable to connect to the cluster",
                        "Connection Failed"
                    )
                    throw e
                }
            },
            "Connecting to OpenShift...",
            true,
            null
        )

        if (success) {
            settings.save(selectedCluster)
        }

        return success
    }

    @Throws(IllegalArgumentException::class)
    private fun createValidatedApiClient(
        server: String,
        token: String,
        errorMessage: String? = null
    ): ApiClient = OpenShiftClientFactory(KubeConfigUtils)
        .create(server, token.toCharArray())
        .also { client ->
            require(Projects(client).isAuthenticated()) { errorMessage ?: "Not authenticated" }
        }

    override fun isNextEnabled(): Boolean {
        if (tfServer.selectedItem == null) return false

        return when (authMethod) {
            AuthMethod.TOKEN  -> tfToken.text.isNotBlank()
            AuthMethod.OPENSHIFT, AuthMethod.SSO -> true
        }
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
                thisLogger().warn(e.message ?: "Could not save configuration file", e)
                Dialogs.error( e.message ?: "Could not save configuration file", "Save Config Failed")
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