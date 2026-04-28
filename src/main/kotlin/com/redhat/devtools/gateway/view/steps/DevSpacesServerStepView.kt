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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.auth.session.RedHatAuthSessionManager
import com.redhat.devtools.gateway.auth.tls.*
import com.redhat.devtools.gateway.auth.tls.ui.UiTlsDecisionAdapter
import com.redhat.devtools.gateway.kubeconfig.FileWatcher
import com.redhat.devtools.gateway.kubeconfig.KubeConfigMonitor
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUpdate
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import com.redhat.devtools.gateway.openshift.Cluster
import com.redhat.devtools.gateway.settings.DevSpacesSettings
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.view.steps.auth.*
import com.redhat.devtools.gateway.view.ui.Dialogs
import com.redhat.devtools.gateway.view.ui.FilteringComboBox
import com.redhat.devtools.gateway.view.ui.PasteClipboardMenu
import com.redhat.devtools.gateway.view.ui.requestInitialFocus
import kotlinx.coroutines.*
import java.awt.event.*
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.UIManager
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

    private var saveToKubeconfig: Boolean = false

    private val saveKubeconfigCheckbox = JBCheckBox(
        DevSpacesBundle.message("connector.wizard_step.openshift_connection.checkbox.save_configuration")
    ).apply {
        isOpaque = false
        background = null
        isSelected = saveToKubeconfig
        addActionListener {
            saveToKubeconfig = isSelected
        }
    }

    private val sessionManager =
        ApplicationManager.getApplication()
            .getService(RedHatAuthSessionManager::class.java)

    private var tfCertAuthorityData = JBTextField()
        .apply {
            document.addDocumentListener(onFieldChanged())
            PasteClipboardMenu.addTo(this)
        }

    private var tfServer =
        FilteringComboBox.create(
            { it?.toString() ?: "" },
            { Cluster.fromNameAndUrl(it) }
        )
        .apply {
            addItemListener(::onClusterSelected)
            val editor = this.editor.editorComponent as JTextField
            PasteClipboardMenu.addTo(editor)
            editor.addKeyListener(createEnterKeyListener())
        }

    private val authStrategies: List<AuthenticationStrategy> by lazy {
        val tokenStrategy = TokenAuthenticationStrategy(
            tfServer,
            ::saveKubeconfig,
            ::saveKubeconfig,
            ::onFieldChanged,
            ::createEnterKeyListener
        )

        val setTokenDisplay: suspend (String) -> Unit = { token ->
            withContext(Dispatchers.Main) {
                tokenStrategy.tfToken.text = token
            }
        }

        listOf(
            tokenStrategy,
            ClientCertificateAuthenticationStrategy(
                tfServer,
                ::saveKubeconfig,
                ::saveKubeconfig,
                ::onFieldChanged
            ),
            OpenShiftOAuthAuthenticationStrategy(
                tfServer,
                ::saveKubeconfig,
                ::saveKubeconfig,
                setTokenDisplay
            ),
            OpenShiftCredentialsAuthenticationStrategy(
                tfServer,
                ::saveKubeconfig,
                ::saveKubeconfig,
                ::onFieldChanged,
                ::createEnterKeyListener,
                setTokenDisplay
            ),
            RedHatSSOAuthenticationStrategy(
                tfServer,
                ::saveKubeconfig,
                ::saveKubeconfig,
                sessionManager
            )
        )
    }

    private var currentStrategy: AuthenticationStrategy? = null
        get() = field ?: authStrategies.firstOrNull().also { field = it }

    private inline fun <reified T : AuthenticationStrategy> findStrategy(): T? =
        authStrategies.firstOrNull { it is T } as? T

    private fun getCurrentAuthTokenValue(): CharArray? =
        when (currentStrategy?.getAuthMethod()) {
            AuthMethod.TOKEN -> (currentStrategy as? TokenAuthenticationStrategy)?.tfToken?.password
            else -> null // other tabs don't have a token yet
        }

    private fun tabPanel(p: JComponent): JComponent =
        p.apply {
            isOpaque = false
            background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
        }

    private val authTabs = JBTabbedPane().apply {
        isOpaque = false
        background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()

        // Add tabs for each strategy
        authStrategies.forEach { strategy ->
            addTab(strategy.getTabTitle(), tabPanel(strategy.createPanel()))
        }

        addChangeListener {
            currentStrategy = authStrategies.getOrNull(selectedIndex)

            saveKubeconfigCheckbox.isVisible =
                currentStrategy?.getAuthMethod() != AuthMethod.REDHAT_SSO

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
        val tabInsets = UIManager.getInsets("TabbedPane.tabInsets") ?: JBUI.insets(0)

        row {
            label(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.authentication"))
                .align(AlignY.TOP)
                // This is the standard "nudge" to align a label with
                // the text baseline of a adjacent component.
                .customize(UnscaledGaps(top = JBUI.scale(16)))

            cell(authTabs)
                .align(AlignX.FILL + AlignY.TOP)
        }.layout(RowLayout.LABEL_ALIGNED)
        row {
            cell(saveKubeconfigCheckbox)
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
        enableNextButton?.invoke()

        findStrategy<TokenAuthenticationStrategy>()?.startMonitoring(component)
    }

    override fun onDispose() {
        findStrategy<TokenAuthenticationStrategy>()?.stopMonitoring()

        stopKubeconfigMonitor()
        super.onDispose()
    }

    private fun onClusterSelected(event: ItemEvent) {
        if (event.stateChange == ItemEvent.SELECTED) {
            (event.item as? Cluster)?.let { selectedCluster ->
                if (allClusters.contains(selectedCluster)) {
                    tfCertAuthorityData.text = selectedCluster.certificateAuthorityData
                    findStrategy<TokenAuthenticationStrategy>()?.tfToken?.apply {
                        text = selectedCluster.token
                    }
                    findStrategy<ClientCertificateAuthenticationStrategy>()?.apply {
                        tfClientCert.text = selectedCluster.clientCertData
                        tfClientKey.text = selectedCluster.clientKeyData
                    }
                    saveKubeconfigCheckbox.isSelected = false
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

        // Only TokenAuthenticationStrategy requires token diff to enable save
        val requiresTokenDiff = currentStrategy is TokenAuthenticationStrategy

        saveKubeconfigCheckbox.isEnabled =
            !allClusters.contains(cluster)
                    || !requiresTokenDiff
                    || tokenChanged
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
            val kubeConfigCurrentCluster = withContext(Dispatchers.IO) {
                KubeConfigUtils.getCurrentClusterName()
            }
            ApplicationManager.getApplication().invokeLater(
                {
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
        val serverDisplay = server.removePrefix("https://").removePrefix("http://")
        val strategy = currentStrategy ?: return false
        var success = false

        if (!confirmAuthSwitchIfNeeded()) return false

        onDispose()

        try {
            runWithModalProgressBlocking(ModalTaskOwner.component(component), "Connecting to OpenShift...") {
                withContext(Dispatchers.IO) {
                    reportRawProgress { reporter ->
                        reporter.text("Connecting to cluster...")

                        val tlsContext = resolveSslContext(server)
                        val certAuthorityData = tfCertAuthorityData.text

                        strategy.authenticate(
                            selectedCluster,
                            server,
                            certAuthorityData,
                            tlsContext,
                            reporter,
                            devSpacesContext
                        )
                    }
                }
            }
            success = true
        } catch (e: AuthenticationException) {
            if (!e.isCancellationException()) {
                Dialogs.error(
                    "Could not connect to cluster $serverDisplay.\n\nReason: ${e.message ?: "Unknown error"}",
                    "Connection Failed"
                )
            }
        } catch (e: Exception) {
            if (!e.isCancellationException()) {
                Dialogs.error(
                    "Could not connect to cluster $serverDisplay: ${e.message ?: "Unknown error"}",
                    "Connection Failed"
                )
            }
        }

        if (success) {
            settings.save(selectedCluster)
        }

        return success
    }

    private fun confirmAuthSwitchIfNeeded(): Boolean {
        val tokenPresent = findStrategy<TokenAuthenticationStrategy>()?.tfToken?.password?.isNotEmpty() == true
        val certStrategy = findStrategy<ClientCertificateAuthenticationStrategy>()
        val certPresent = certStrategy?.tfClientCert?.text?.isNotBlank() == true
                || certStrategy?.tfClientKey?.text?.isNotBlank() == true

        val (message, shouldAsk) = when (currentStrategy?.getAuthMethod()) {
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

    override fun isNextEnabled(): Boolean =
        currentStrategy?.isNextEnabled() ?: false

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
            withContext(Dispatchers.IO) {
                KubeConfigUtils.getAllConfigs(
                    KubeConfigUtils.getAllConfigFiles()
                )
            }
        },
        kubeConfigWriter = { namedCluster, certs ->
            withContext(Dispatchers.IO) {
                KubeConfigTlsWriter.write(namedCluster, certs)
            }
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

    private fun saveKubeconfig(cluster: Cluster?, token: String?, reporter: RawProgressReporter) {
        if (!saveToKubeconfig || cluster == null || token.isNullOrBlank()) return

        try {
            reporter.text("Updating Kube config...")
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

    private fun saveKubeconfig(cluster: Cluster?, clientCertPem: String?, clientKeyPem: String?, reporter: RawProgressReporter) {
        if (!saveToKubeconfig || cluster == null || clientCertPem.isNullOrBlank() || clientKeyPem.isNullOrBlank()) return

        try {
            reporter.text("Updating Kube config...")
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
        findStrategy<TokenAuthenticationStrategy>()?.tfToken?.apply {
            text = toSelect?.token ?: ""
        }
        findStrategy<ClientCertificateAuthenticationStrategy>()?.apply {
            tfClientCert.text = toSelect?.clientCertData ?: ""
            tfClientKey.text = toSelect?.clientKeyData ?: ""
        }
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