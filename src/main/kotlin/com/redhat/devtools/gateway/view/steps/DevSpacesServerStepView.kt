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
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.redhat.devtools.gateway.auth.tls.browseCertificate
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
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
import com.redhat.devtools.gateway.view.steps.auth.*
import com.redhat.devtools.gateway.view.ui.Dialogs
import com.redhat.devtools.gateway.view.ui.FilteringComboBox
import com.redhat.devtools.gateway.view.ui.PasteClipboardMenu
import com.redhat.devtools.gateway.view.ui.requestInitialFocus
import com.redhat.devtools.gateway.util.isLoginUserCancelled
import kotlinx.coroutines.*
import java.awt.event.ItemEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class DevSpacesServerStepView(
    private var devSpacesContext: DevSpacesContext,
    private val enableNextButton: (() -> Unit)?,
    private val triggerNextAction: (() -> Unit)? = null
) : DevSpacesWizardStep {

    private lateinit var allClusters: List<Cluster>

    /**
     * `clusters[].name` referenced by kubeconfig `current-context`; from [KubeConfigUtils.getCurrentClusterName].
     * When the Server combo shows another cluster, the form is dirty vs that default.
     */
    private var currentContextClusterName: String? = null

    private val settings: ServerSettings = ServerSettings()

    private lateinit var kubeconfigScope: CoroutineScope
    private lateinit var kubeconfigMonitor: KubeConfigMonitor

    private val saveConfigCheckbox = JBCheckBox(
        DevSpacesBundle.message("connector.wizard_step.openshift_connection.checkbox.save_configuration")
    ).apply {
        isOpaque = false
        background = null
        isSelected = false
    }

    private val saveConfig: Boolean
        get() = saveConfigCheckbox.isEnabled && saveConfigCheckbox.isSelected

    private val sessionManager =
        ApplicationManager.getApplication()
            .getService(RedHatAuthSessionManager::class.java)

    private var tfCertAuthority = JBTextField()
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
            editor.document.addDocumentListener(onFieldChanged())
        }

    private val authStrategies: List<AuthenticationStrategy> by lazy {
        val tokenStrategy = TokenAuthenticationStrategy(
            tfServer,
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
            OpenShiftOAuthAuthenticationStrategy(
                tfServer,
                ::saveKubeconfig,
                setTokenDisplay
            ),
            ClientCertificateAuthenticationStrategy(
                tfServer,
                ::saveKubeconfig,
                ::saveKubeconfigWithCert,
                ::onFieldChanged
            ),
            OpenShiftCredentialsAuthenticationStrategy(
                tfServer,
                ::saveKubeconfig,
                ::onFieldChanged,
                ::createEnterKeyListener,
                setTokenDisplay
            ),
            RedHatSSOAuthenticationStrategy(
                tfServer,
                ::saveKubeconfig,
                sessionManager
            )
        )
    }

    private var currentStrategy: AuthenticationStrategy? = null
        get() = field ?: authStrategies.firstOrNull().also { field = it }

    private inline fun <reified T : AuthenticationStrategy> findStrategy(): T? =
        authStrategies.firstOrNull { it is T } as? T

    private fun tabPanel(p: JComponent): JComponent =
        JBUI.Panels.simplePanel(p).apply {
            isOpaque = true
            background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
            border = JBUI.Borders.emptyTop(16)
        }

    private val authTabs = JBTabbedPane().apply {
        isOpaque = true
        background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()

        // Add tabs for each strategy
        authStrategies.forEach { strategy ->
            val panel = strategy.createPanel()
            // Make inner panel transparent so wrapper background shows through
            panel.isOpaque = false
            addTab(strategy.getTabTitle(), tabPanel(panel))
        }

        addChangeListener { event ->
            currentStrategy = authStrategies.getOrNull(selectedIndex)
            enableNextButton?.invoke()
            enableSaveConfigCheckbox()
        }
    }

    val bodyPanel = panel {
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.server")) {
            cell(tfServer).align(Align.FILL)
        }
        collapsibleGroup(
            DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.advanced_group")
        ) {
            row(
                DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.certificate_authority")
            ) {
                cell(tfCertAuthority)
                    .align(Align.FILL)
                    .resizableColumn()
                    .comment("Provide the path to a PEM file or paste the PEM content")
                button("Browse...") {
                    browseCertificate(tfCertAuthority, "Select Certificate Authority File")
                }
            }
        }
        group(
            DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.authentication")
        ) {
            row {
                cell(authTabs)
                    .align(Align.FILL)
            }
        }
        row {
            cell(saveConfigCheckbox)
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
                if (    allClusters.contains(selectedCluster)) {
                    tfCertAuthority.text = selectedCluster.certificateAuthority?.value ?: ""
                    findStrategy<TokenAuthenticationStrategy>()?.tfToken?.apply {
                        text = selectedCluster.token
                    }
                    findStrategy<ClientCertificateAuthenticationStrategy>()?.apply {
                        tfClientCert.text = selectedCluster.clientCert?.value ?: ""
                        tfClientKey.text = selectedCluster.clientKey?.value ?: ""
                    }
                    findStrategy<OpenShiftCredentialsAuthenticationStrategy>()?.applyFromCluster(selectedCluster)
                    saveConfigCheckbox.isSelected = false
                }
            }
        }
        enableSaveConfigCheckbox()
    }

    private fun onFieldChanged(): DocumentListener = object : DocumentListener {
        override fun insertUpdate(event: DocumentEvent) {
            enableNextButton?.invoke()
            enableSaveConfigCheckbox()
        }

        override fun removeUpdate(e: DocumentEvent) {
            enableNextButton?.invoke()
            enableSaveConfigCheckbox()
        }

        override fun changedUpdate(e: DocumentEvent?) {
            enableNextButton?.invoke()
            enableSaveConfigCheckbox()
        }
    }

    /**
     * Returns the cluster that is selected.
     * When the editor text does not parse as a [Cluster], we must not fall back to the combo's stale `selectedItem`:
     * that would keep the old cluster and hide URL edits from [isDirty].
     *
     * @return the cluster that is selected or null
     */
    private fun getSelectedCluster(): Cluster? {
        val parsed = tfServer.editor.item as? Cluster
        if (parsed != null) return parsed
        val selected = tfServer.selectedItem as? Cluster ?: return null
        val text = (tfServer.editor.editorComponent as JTextField).text.trim()
        return selected.takeIf { it.toString() == text }
    }

    /**
     * Returns `true` if the values in the form are dirty and may be saved.
     * It is considered dirty if the following values differ from kube config:
     * * cluster name or URL/CA/auth
     * * selected auth strategy
     * * auth strategy values
     *
     * @see [getKubeConfigFor]
     * @see [AuthenticationStrategy.isDirty]
     */
    private fun isDirty(): Boolean {
        val cluster = getSelectedCluster() ?: return true
        val ctxName = currentContextClusterName
        if (ctxName != null && cluster.name != ctxName) return true

        val config = getKubeConfigFor(cluster) ?: return true

        if (cluster.url != config.url) return true

        if (tfCertAuthority.text.trim() != (config.certificateAuthority?.value ?: "").trim()) return true

        return currentStrategy?.isDirty(config) ?: true
    }

    /**
     * Kubeconfig row for [cluster]. [Cluster.id] is name+URL — id match is exact until URL is edited;
     * then we match by name so URL/CA/auth still compare to the saved entry.
     */
    private fun getKubeConfigFor(cluster: Cluster): Cluster? =
        allClusters.find { it.id == cluster.id }
            ?: allClusters.find { it.name == cluster.name }

    private fun enableSaveConfigCheckbox() {
        val isDirty = isDirty()
        saveConfigCheckbox.isEnabled = isDirty
        if (!isDirty) {
            saveConfigCheckbox.isSelected = false // uncheck if checkbox is disabled
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
            val kubeConfigCurrentCluster = withContext(Dispatchers.IO) {
                KubeConfigUtils.getCurrentClusterName()
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    currentContextClusterName = kubeConfigCurrentCluster
                    val previouslySelected = tfServer.selectedItem as? Cluster?
                    setClusters(updatedClusters)
                    setSelectedCluster(
                        (previouslySelected)?.name ?: kubeConfigCurrentCluster,
                        updatedClusters
                    )
                    enableSaveConfigCheckbox()
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
        val selectedCluster = getSelectedCluster() ?: return false
        val server = selectedCluster.url
        val serverDisplay = server.removePrefix("https://").removePrefix("http://")
        val strategy = currentStrategy ?: return false

        if (!confirmAuthSwitchIfNeeded()) return false

        onDispose()

        var authResult: Result<Unit>? = null

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                runBlocking {
                    val indicator = ProgressManager.getInstance().progressIndicator
                    indicator.text = "Connecting to cluster..."

                    try {
                        val tlsContext = resolveSslContext(server)
                        val certAuthorityData = tfCertAuthority.text.ifBlank { null }

                        strategy.authenticate(
                            selectedCluster,
                            server,
                            certAuthorityData,
                            tlsContext,
                            devSpacesContext,
                            indicator
                        )
                        authResult = Result.success(Unit)
                    } catch (e: Exception) {
                        authResult = Result.failure(e)
                    }
                }
            },
            "Connecting to OpenShift...",
            true,
            null
        )

        val result = authResult!!
        return result.fold(
            onSuccess = {
                settings.save(selectedCluster)
                true
            },
            onFailure = { e ->
                thisLogger().warn(e)
                if (!e.isLoginUserCancelled()) {
                    Dialogs.error(
                        "Could not connect to cluster $serverDisplay.\n\nReason: ${e.message ?: "Unknown error"}",
                        "Connection Failed"
                    )
                }
                false
            }
        )
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

    private suspend fun saveKubeconfig(cluster: Cluster, token: String, indicator: ProgressIndicator) {
        if (!saveConfig || token.isBlank()) return

        try {
            indicator.text = "Updating Kube config..."
            withContext(Dispatchers.IO) {
                KubeConfigUpdate
                    .create(
                        cluster.name.trim(),
                        cluster.url.trim(),
                        token.trim())
                    .apply()
            }

        } catch (e: Exception) {
            thisLogger().warn(e.message ?: "Could not save configuration file", e)
            withContext(Dispatchers.Main) {
                Dialogs.error(e.message ?: "Could not save configuration file", "Save Config Failed")
            }
        }
    }

    private suspend fun saveKubeconfigWithCert(cluster: Cluster, clientCertPem: String, clientKeyPem: String, indicator: ProgressIndicator) {
        if (!saveConfig
            || clientCertPem.isBlank()
            || clientKeyPem.isBlank())
            return

        try {
            indicator.text = "Updating Kube config..."
            withContext(Dispatchers.IO) {
                KubeConfigUpdate
                    .create(
                        cluster.name.trim(),
                        cluster.url.trim(),
                        clientCertPem.trim(),
                        clientKeyPem.trim())
                    .apply()
            }
        } catch (e: Exception) {
            thisLogger().warn(e.message ?: "Could not save configuration file", e)
            withContext(Dispatchers.Main) {
                Dialogs.error(e.message ?: "Could not save configuration file", "Save Config Failed")
            }
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
        tfCertAuthority.text = toSelect?.certificateAuthority?.value ?: ""
        findStrategy<TokenAuthenticationStrategy>()?.tfToken?.apply {
            text = toSelect?.token ?: ""
        }
        findStrategy<ClientCertificateAuthenticationStrategy>()?.apply {
            tfClientCert.text = toSelect?.clientCert?.value ?: ""
            tfClientKey.text = toSelect?.clientKey?.value ?: ""
        }
        findStrategy<OpenShiftCredentialsAuthenticationStrategy>()?.applyFromCluster(toSelect)
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