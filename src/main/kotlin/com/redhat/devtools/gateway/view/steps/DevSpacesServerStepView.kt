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

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.auth.code.AuthTokenKind
import com.redhat.devtools.gateway.auth.code.TokenModel
import com.redhat.devtools.gateway.auth.sandbox.SandboxClusterAuthProvider
import com.redhat.devtools.gateway.auth.session.LOGIN_TIMEOUT_MS
import com.redhat.devtools.gateway.auth.session.OpenShiftAuthSessionManager
import com.redhat.devtools.gateway.auth.session.RedHatAuthSessionManager
import com.redhat.devtools.gateway.auth.tls.*
import com.redhat.devtools.gateway.auth.tls.ui.UiTlsDecisionAdapter
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
import io.kubernetes.client.openapi.ApiClient
import kotlinx.coroutines.*
import java.awt.Cursor
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private const val SAVE_REQUIRES_TOKEN_DIFF =
    "devspaces.save.requires.token.diff"

class DevSpacesServerStepView(
    private var devSpacesContext: DevSpacesContext,
    private val enableNextButton: (() -> Unit)?,
    private val triggerNextAction: (() -> Unit)? = null
) : DevSpacesWizardStep {

    private lateinit var allClusters: List<Cluster>

    private val settings: ServerSettings = ServerSettings()

    private lateinit var kubeconfigScope: CoroutineScope
    private lateinit var kubeconfigMonitor: KubeConfigMonitor

    private var saveToKubeconfig: Boolean = false
    private val saveKubeconfigCheckboxes = mutableListOf<JBCheckBox>()

    private fun syncSaveKubeconfigCheckboxes(source: JBCheckBox) {
        saveKubeconfigCheckboxes
            .filter { it !== source }
            .forEach { it.isSelected = saveToKubeconfig }
    }

    private fun createSaveKubeconfigCheckbox(
        requiresTokenDiff: Boolean? = false
    ): JBCheckBox =
        JBCheckBox(DevSpacesBundle.message("connector.wizard_step.openshift_connection.checkbox.save_configuration")).apply {
            isOpaque = false
            background = null

            isSelected = saveToKubeconfig

            putClientProperty(SAVE_REQUIRES_TOKEN_DIFF, requiresTokenDiff)

            addActionListener {
                saveToKubeconfig = isSelected
                syncSaveKubeconfigCheckboxes(this)
            }

            saveKubeconfigCheckboxes += this
        }

    private val updateKubeconfigCheckbox = JBCheckBox(DevSpacesBundle.message("connector.wizard_step.openshift_connection.checkbox.save_configuration"))

    private val sessionManager =
        ApplicationManager.getApplication()
            .getService(RedHatAuthSessionManager::class.java)

    private var lastClipboardValue: String? = null
    private var clipboardPollingJob: Job? = null

    fun startClipboardPolling() {
        clipboardPollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val value = readClipboardText()

                if (value != null && value != lastClipboardValue) {
                    lastClipboardValue = value

                    suggestToken(value)
                }

                delay(500)
            }
        }
    }

    fun readClipboardText(): String? {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val contents = clipboard.getContents(null) ?: return null

        return try {
            if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                contents.getTransferData(DataFlavor.stringFlavor) as? String
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }?.trim()
    }

    fun stopClipboardPolling() {
        clipboardPollingJob?.cancel()
        clipboardPollingJob = null
    }

    private val OPENSHIFT_TOKEN_REGEX =
        Regex("^sha256~[A-Za-z0-9_-]{20,}$")

    fun String?.isOpenShiftToken(): Boolean =
        this?.let { OPENSHIFT_TOKEN_REGEX.matches(it.trim()) } == true

    private fun checkClipboardForToken() {
        val token = readClipboardText()
        if (token.isOpenShiftToken()) {
            suggestToken(token)
        }
    }

    private fun suggestToken(token: String?) {
        ApplicationManager.getApplication().invokeLater (
            {
                if (token.isOpenShiftToken() == true) {
                    tokenSuggestionLabel.apply {
                        text = "Token detected in clipboard. Click here to use it."
                        isVisible = true
                        isEnabled = true
                    }
                } else {
                    tokenSuggestionLabel.apply {
                        isVisible = false
                        isEnabled = false
                    }
                }
            },
            ModalityState.stateForComponent(component)
        )
    }

    private val tokenSuggestionLabel = JBLabel()
        .apply {
            text = ""
            foreground = JBColor.BLUE
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isVisible = false 
            font = font.deriveFont(Font.ITALIC or Font.PLAIN)
        }

    private var tokenLabelListener: MouseAdapter? = null

    private fun setupTokenSuggestionLabel() {
        tokenLabelListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                val token = lastClipboardValue ?: return
                tfToken.text = token
                tokenSuggestionLabel.isVisible = false
            }
        }
        tokenSuggestionLabel.addMouseListener(tokenLabelListener)
        tokenSuggestionLabel.isVisible = false
    }

    private var tfToken = JBPasswordField()
        .apply {
            document.addDocumentListener(onFieldChanged())
            PasteClipboardMenu.addTo(this)
            addKeyListener(createEnterKeyListener())
        }

    private var tfCertAuthorityData = JBTextField()
        .apply {
            document.addDocumentListener(onFieldChanged())
            PasteClipboardMenu.addTo(this)
        }

    private val tfUsername = JBTextField()
        .apply {
            document.addDocumentListener(onFieldChanged())
            PasteClipboardMenu.addTo(this)
            addKeyListener(createEnterKeyListener())
        }
    private val tfPassword = JBPasswordField()
        .apply {
            document.addDocumentListener(onFieldChanged())
            PasteClipboardMenu.addTo(this)
            addKeyListener(createEnterKeyListener())
        }
    private val tfClientCert = JBTextField()
        .apply {
            document.addDocumentListener(onFieldChanged())
            PasteClipboardMenu.addTo(this)
        }
    private val tfClientKey = JBTextField()
        .apply {
            document.addDocumentListener(onFieldChanged())
            PasteClipboardMenu.addTo(this)
        }
    private val showTokenCheckbox = JBCheckBox(DevSpacesBundle.message("connector.wizard_step.openshift_connection.checkbox.show_token"))
        .apply {
            isOpaque = false
            background = null
        }
    private val showPasswordCheckbox = JBCheckBox(DevSpacesBundle.message("connector.wizard_step.openshift_connection.checkbox.show_password"))
        .apply {
            isOpaque = false
            background = null
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
        TOKEN,                  // User token
        CLIENT_CERTIFICATE,     // Client certificate
        OPENSHIFT,              // browser PKCE
        OPENSHIFT_CREDENTIALS,  // username/password
        REDHAT_SSO              // RH SSO (Sandbox)
    }

    private var authMethod: AuthMethod = AuthMethod.TOKEN


    private fun updateAuthUiState() {
        enableNextButton?.invoke()
    }

    private fun getCurrentAuthTokenValue(): CharArray? =
        when (authMethod) {
            AuthMethod.TOKEN -> tfToken.password
            else -> null // other tabs don't have a token yet
        }

    private fun tokenPanel() = panel {
        row {
            cell(tokenSuggestionLabel).align(Align.FILL)
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.token")) {
            cell(tfToken).align(Align.FILL)
        }
        row {
            cell(showTokenCheckbox)
        }
        row {
            cell(createSaveKubeconfigCheckbox(true).also { saveKubeconfigCheckboxes += it })
        }
    }

    private fun clientCertificatePanel() = panel {
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.client_certificate")) {
            cell(tfClientCert).align(Align.FILL)
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.client_key")) {
            cell(tfClientKey).align(Align.FILL)
        }
        row {
            cell(createSaveKubeconfigCheckbox().also { saveKubeconfigCheckboxes += it })
        }
    }

    private fun openShiftOAuthPanel() = panel {
        row {
            label(DevSpacesBundle.message("connector.wizard_step.openshift_connection.text.openshift_oauth_info"))
        }
        row {
            cell(createSaveKubeconfigCheckbox().also { saveKubeconfigCheckboxes += it })
        }
    }

    private fun credentialsPanel() = panel {
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.username")) {
            cell(tfUsername).align(Align.FILL)
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.password")) {
            cell(tfPassword).align(Align.FILL)
        }
        row {
            cell(showPasswordCheckbox)
        }
        row {
            cell(createSaveKubeconfigCheckbox().also { saveKubeconfigCheckboxes += it })
        }
    }

    private fun redHatSSOPanel() = panel {
        row {
            label(DevSpacesBundle.message("connector.wizard_step.openshift_connection.text.redhat_sso_info"))
        }
        row {
            label(
                DevSpacesBundle.message("connector.wizard_step.openshift_connection.text.redhat_sso_token_note")
            ).comment(
                DevSpacesBundle.message("connector.wizard_step.openshift_connection.text.pipeline_token_comment")
            )
        }
    }

    private fun tabPanel(p: JComponent): JComponent =
        p.apply {
            isOpaque = false
            background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
        }

    private val authTabs = JBTabbedPane().apply {
        isOpaque = false
        background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()

        addTab(
            DevSpacesBundle.message("connector.wizard_step.openshift_connection.tab.token"),
            tabPanel(tokenPanel()))
        addTab(
            DevSpacesBundle.message("connector.wizard_step.openshift_connection.tab.client_certificate"),
            tabPanel(clientCertificatePanel())
        )
        addTab(
            DevSpacesBundle.message("connector.wizard_step.openshift_connection.tab.openshift_oauth"),
            tabPanel(openShiftOAuthPanel()))
        addTab(
            DevSpacesBundle.message("connector.wizard_step.openshift_connection.tab.credentials"),
            tabPanel(credentialsPanel()))
        addTab(
            DevSpacesBundle.message("connector.wizard_step.openshift_connection.tab.redhat_sso"),
            tabPanel(redHatSSOPanel()))

        addChangeListener {
            authMethod = when (selectedIndex) {
                0 -> AuthMethod.TOKEN
                1 -> AuthMethod.CLIENT_CERTIFICATE
                2 -> AuthMethod.OPENSHIFT
                3 -> AuthMethod.OPENSHIFT_CREDENTIALS
                else -> AuthMethod.REDHAT_SSO
            }

            updateKubeconfigCheckbox.isVisible =
                authMethod != AuthMethod.REDHAT_SSO

            enableNextButton?.invoke()
        }
    }

    val bodyPanel = panel {
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.server")) {
            cell(tfServer).align(Align.FILL)
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.certificate_authority")) {
            cell(tfCertAuthorityData).align(Align.FILL)
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.authentication")) {
            cell(authTabs).align(Align.FILL)
        }
    }.apply {
        isOpaque = false
        background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
    }

    override val component = panel {
        row {
            label(DevSpacesBundle.message("connector.wizard_step.openshift_connection.title")).applyToComponent {
                font = JBFont.h2().asBold()
            }
        }
        row {
            cell(bodyPanel).align(AlignX.FILL).align(AlignY.FILL)
        }.resizableRow()
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
        setupTokenSuggestionLabel()
        startClipboardPolling()
        checkClipboardForToken()

        showTokenCheckbox.addActionListener {
            tfToken.echoChar = if (showTokenCheckbox.isSelected) 0.toChar() else '•'
        }

        showPasswordCheckbox.addActionListener {
            tfPassword.echoChar = if (showPasswordCheckbox.isSelected) 0.toChar() else '•'
        }
    }

    override fun onDispose() {
        tokenLabelListener?.let {
            tokenSuggestionLabel.removeMouseListener(it)
        }
        stopClipboardPolling()
        stopKubeconfigMonitor()
        super.onDispose()
    }

    private fun onClusterSelected(event: ItemEvent) {
        if (event.stateChange == ItemEvent.SELECTED) {
            (event.item as? Cluster)?.let { selectedCluster ->
                if (allClusters.contains(selectedCluster)) {
                    tfCertAuthorityData.text = selectedCluster.certificateAuthorityData
                    tfToken.text = selectedCluster.token
                    tfClientCert.text = selectedCluster.clientCertData
                    tfClientKey.text = selectedCluster.clientKeyData
                    updateKubeconfigCheckbox.isSelected = false
                }
            }
        }
        updateSaveKubeconfigCheckboxEnablement()
    }

    private fun onFieldChanged(): DocumentListener = object : DocumentListener {
        override fun insertUpdate(event: DocumentEvent) {
            enableNextButton?.invoke()
            updateSaveKubeconfigCheckboxEnablement()
        }

        override fun removeUpdate(e: DocumentEvent) {
            enableNextButton?.invoke()
            updateSaveKubeconfigCheckboxEnablement()
        }

        override fun changedUpdate(e: DocumentEvent?) {
            enableNextButton?.invoke()
            updateSaveKubeconfigCheckboxEnablement()
        }
    }

    private fun updateSaveKubeconfigCheckboxEnablement() {
        val cluster = tfServer.selectedItem as? Cluster
        val currentToken = getCurrentAuthTokenValue()

        val tokenChanged =
            !cluster?.token.isNullOrBlank()
                    && currentToken?.isNotEmpty() == true
                    && !cluster.token.toCharArray().contentEquals(currentToken)

        saveKubeconfigCheckboxes.forEach { checkbox ->
            val requiresTokenDiff =
                checkbox.getClientProperty(SAVE_REQUIRES_TOKEN_DIFF) as? Boolean ?: false

            checkbox.isEnabled =
                !allClusters.contains(cluster)
                        || !requiresTokenDiff
                        || tokenChanged
        }
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
            ApplicationManager.getApplication().invokeLater(
                {
                    val kubeConfigCurrentCluster = KubeConfigUtils.getCurrentClusterName()
                    val previouslySelected = tfServer.selectedItem as? Cluster?
                    setClusters(updatedClusters)
                    setSelectedCluster(
                        (previouslySelected)?.name ?: kubeConfigCurrentCluster,
                        updatedClusters
                    )
                    updateSaveKubeconfigCheckboxEnablement()
                },
                ModalityState.stateForComponent(component)
            )
        }
    }

    override fun onPrevious(): Boolean {
        onDispose()
        return true
    }

    override fun onNext(): Boolean {
        val selectedCluster = tfServer.selectedItem as? Cluster ?: return false
        val server = selectedCluster.url
        var success = false

        if (!confirmAuthSwitchIfNeeded()) return false

        onDispose()

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                val indicator = ProgressManager.getInstance().progressIndicator

                try {
                    indicator.text = "Connecting to cluster..."

                    val tlsContext = runBlocking {
                        resolveSslContext(server)
                    }

                    val certAuthorityData = tfCertAuthorityData.text

                    when (authMethod) {
                        AuthMethod.TOKEN -> {
                            indicator.text = "Validating token..."

                            val token = String(tfToken.password)

                            val client = createValidatedApiClient(server, certAuthorityData,
                                token, null, null, tlsContext,
                                "Authentication failed: invalid server URL or token.")

                            saveKubeconfig(selectedCluster, token, indicator)
                            devSpacesContext.client = client
                        }

                        AuthMethod.CLIENT_CERTIFICATE -> {
                            indicator.text = "Validating client certificate..."

                            val clientCertPem = tfClientCert.text
                            val clientKeyPem = tfClientKey.text

                            val client = createValidatedApiClient(server, certAuthorityData,
                                null, clientCertPem, clientKeyPem, tlsContext,
                                "Authentication failed: invalid server URL or token.")

                            require(Projects(client).isAuthenticated()) {
                                "Authentication failed: invalid client certificate or key."
                            }

                            saveKubeconfig(selectedCluster, clientCertPem, clientKeyPem, indicator)
                            devSpacesContext.client = client
                        }

                        AuthMethod.OPENSHIFT_CREDENTIALS -> {
                            indicator.text = "Authenticating with OpenShift credentials..."

                            val username = tfUsername.text
                            val password = String(tfPassword.password)

                            val finalToken = runBlocking {
                                val sessionManager = OpenShiftAuthSessionManager()

                                val osToken = sessionManager.loginWithCredentials(
                                    apiServerUrl = selectedCluster.url,
                                    username = username,
                                    password = password,
                                    tlsContext.sslContext
                                )

                                TokenModel(
                                    accessToken = osToken.accessToken,
                                    expiresAt = osToken.expiresAt,
                                    accountLabel = osToken.accountLabel,
                                    kind = AuthTokenKind.TOKEN,
                                    clusterApiUrl = selectedCluster.url
                                )
                            }

                            indicator.text = "Validating cluster access..."

                            val client = createValidatedApiClient(server, certAuthorityData,
                                finalToken.accessToken, null, null, tlsContext,
                                "Authentication failed: invalid OpenShift credentials."
                            )

                            tfToken.text = finalToken.accessToken
                            saveKubeconfig(selectedCluster, finalToken.accessToken, indicator)
                            devSpacesContext.client = client
                        }

                        AuthMethod.OPENSHIFT -> {
                            indicator.text = "Authenticating with Openshift..."

                            val finalToken = runBlocking {
                                val openshiftSSessionManager = OpenShiftAuthSessionManager()
                                val uri = openshiftSSessionManager.startLogin(selectedCluster.url, tlsContext.sslContext)
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

                            val client = createValidatedApiClient(server, certAuthorityData,
                                finalToken.accessToken, null, null, tlsContext,
                                "Authentication failed: token received from OpenShift Authenticator is invalid or expired.")

                            tfToken.text = finalToken.accessToken
                            saveKubeconfig(selectedCluster, finalToken.accessToken, indicator)
                            devSpacesContext.client = client
                        }

                        AuthMethod.REDHAT_SSO -> {
                            indicator.text = "Authenticating with Red Hat..."

                            val finalToken = runBlocking {
                                val uri = sessionManager.startLogin(sslContext = tlsContext.sslContext)
                                BrowserUtil.browse(uri)

                                indicator.text = "Waiting for you to complete login in your browser..."
                                indicator.checkCanceled()

                                val ssoToken = sessionManager.awaitLoginResult(LOGIN_TIMEOUT_MS)
                                indicator.text = "Obtaining OpenShift access..."

                                val sandboxAuth = SandboxClusterAuthProvider()
                                sandboxAuth.authenticate(ssoToken)
                            }

                            indicator.text = "Validating cluster access..."

                            val client = createValidatedApiClient(server, certAuthorityData,
                                finalToken.accessToken, null, null, tlsContext,
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

    private fun confirmAuthSwitchIfNeeded(): Boolean {
        val tokenPresent = tfToken.password.isNotEmpty()
        val certPresent = tfClientCert.text.isNotBlank() || tfClientKey.text.isNotBlank()

        val (message, shouldAsk) = when (authMethod) {
            AuthMethod.TOKEN -> {
                if (certPresent) {
                    "Switching to token authentication will remove the configured client certificate. Continue?" to true
                } else null to false
            }

            AuthMethod.CLIENT_CERTIFICATE -> {
                if (tokenPresent) {
                    "Switching to client certificate authentication will remove the configured token. Continue?" to true
                } else null to false
            }

            else -> null to false
        }

        if (!shouldAsk || message == null) return true

        return MessageDialogBuilder
            .yesNo(
                "Change Authentication Method",
                message
            )
            .yesText("Switch")
            .noText("Cancel")
            .ask(component)
    }

    @Throws(IllegalArgumentException::class)
    private fun createValidatedApiClient(
        server: String,
        certificateAuthorityData: String? = null,
        token: String? = null,
        clientCertPem: String? = null,
        clientKeyPem: String? = null,
        tlsContext: TlsContext,
        errorMessage: String? = null
    ): ApiClient = OpenShiftClientFactory(KubeConfigUtils)
        .create(server, certificateAuthorityData?.toCharArray(), token?.toCharArray(),
            clientCertPem?.toCharArray(), clientKeyPem?.toCharArray(), tlsContext)
        .also { client ->
            require(Projects(client).isAuthenticated()) { errorMessage ?: "Not authenticated" }
        }

    override fun isNextEnabled(): Boolean =
        when (authMethod) {
            AuthMethod.TOKEN ->
                tfToken.password?.isNotEmpty() == true

            AuthMethod.CLIENT_CERTIFICATE ->
                tfClientCert.text.isNotBlank() && tfClientKey.text.isNotBlank()

            AuthMethod.OPENSHIFT_CREDENTIALS ->
                tfUsername.text.isNotBlank() &&
                        tfPassword.password?.isNotEmpty() == true

            AuthMethod.OPENSHIFT,
            AuthMethod.REDHAT_SSO ->
                tfServer.selectedItem != null
        }

    private val sessionTrustStore = SessionTlsTrustStore()
    private val persistentKeyStore = PersistentKeyStore(
        path = Paths.get(
            PathManager.getConfigPath(),
            "devspaces",
            "tls-truststore.p12"
        ),
        password = CharArray(0)
    )

    private val tlsTrustManager = DefaultTlsTrustManager(
        kubeConfigProvider = {
            KubeConfigUtils.getAllConfigs(
                KubeConfigUtils.getAllConfigFiles()
            )
        },
        kubeConfigWriter = { namedCluster, certs ->
            KubeConfigTlsWriter.write(namedCluster, certs)
        },
        sessionTrustStore = sessionTrustStore,
        persistentKeyStore = persistentKeyStore
    )

    private suspend fun resolveSslContext(serverUrl: String): TlsContext {
        return tlsTrustManager.ensureTrusted(
            serverUrl = serverUrl,
            decisionHandler = UiTlsDecisionAdapter::decide
        )
    }

    private fun saveKubeconfig(cluster: Cluster?, token: String?, indicator: ProgressIndicator) {
        if (!saveToKubeconfig || cluster == null || token.isNullOrBlank()) return

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

    private fun saveKubeconfig(cluster: Cluster?, clientCertPem: String?, clientKeyPem: String?, indicator: ProgressIndicator) {
        if (!saveToKubeconfig || cluster == null || clientCertPem.isNullOrBlank() || clientKeyPem.isNullOrBlank()) return

        try {
            indicator.text = "Updating Kube config..."
            KubeConfigUpdate
                .create(
                    cluster.name.trim(),
                    cluster.url.trim(),
                    clientCertPem.trim(),
                    clientKeyPem.trim())
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
        tfCertAuthorityData.text = toSelect?.certificateAuthorityData ?: ""
        tfToken.text = toSelect?.token ?: ""
        tfClientCert.text = toSelect?.clientCertData ?: ""
        tfClientKey.text = toSelect?.clientKeyData ?: ""
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