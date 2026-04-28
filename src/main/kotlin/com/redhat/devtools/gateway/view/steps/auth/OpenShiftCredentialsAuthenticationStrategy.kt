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
package com.redhat.devtools.gateway.view.steps.auth

import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import javax.swing.event.DocumentListener
import java.awt.event.KeyListener
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.view.ui.PasteClipboardMenu
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.auth.code.AuthTokenKind
import com.redhat.devtools.gateway.auth.code.TokenModel
import com.redhat.devtools.gateway.auth.session.OpenShiftAuthSessionManager
import com.redhat.devtools.gateway.auth.tls.TlsContext
import com.redhat.devtools.gateway.openshift.Cluster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JPanel

/**
 * Authentication strategy for OpenShift credentials (username/password).
 */
@Suppress("UnstableApiUsage")
class OpenShiftCredentialsAuthenticationStrategy(
    tfServer: Any,
    saveKubeconfig: suspend (Cluster, String, RawProgressReporter) -> Unit,
    saveKubeconfigCert: suspend (Cluster, String, String, RawProgressReporter) -> Unit,
    private val onFieldChanged: () -> DocumentListener,
    private val createEnterKeyListener: () -> KeyListener,
    private val setTokenDisplay: suspend (String) -> Unit
) : AbstractAuthenticationStrategy(
    tfServer,
    saveKubeconfig,
    saveKubeconfigCert
) {

    private val tfUsername = JBTextField().apply {
        document.addDocumentListener(onFieldChanged())
        PasteClipboardMenu.addTo(this)
        addKeyListener(createEnterKeyListener())
    }

    private val tfPassword = JBPasswordField().apply {
        document.addDocumentListener(onFieldChanged())
        PasteClipboardMenu.addTo(this)
        addKeyListener(createEnterKeyListener())
    }

    private val showPasswordCheckbox = JBCheckBox(
        DevSpacesBundle.message("connector.wizard_step.openshift_connection.checkbox.show_password")
    ).apply {
        isOpaque = false
        background = null
        addActionListener {
            tfPassword.echoChar = if (isSelected) 0.toChar() else '•'
        }
    }

    override fun getAuthMethod(): AuthMethod = AuthMethod.OPENSHIFT_CREDENTIALS

    override fun getTabTitle(): String =
        DevSpacesBundle.message("connector.wizard_step.openshift_connection.tab.credentials")

    override fun createPanel(): JPanel = panel {
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.username")) {
            cell(tfUsername).align(Align.FILL)
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.password")) {
            cell(tfPassword).align(Align.FILL)
        }
        row {
            cell(showPasswordCheckbox)
        }
    }

    override suspend fun authenticate(
        selectedCluster: Cluster,
        server: String,
        certAuthorityData: String,
        tlsContext: TlsContext,
        reporter: RawProgressReporter,
        devSpacesContext: DevSpacesContext
    ) {
        reporter.text("Authenticating with OpenShift credentials...")

        val username = tfUsername.text
        val password = String(tfPassword.password)

        val sessionManager = OpenShiftAuthSessionManager()

        val osToken = sessionManager.loginWithCredentials(
            apiServerUrl = selectedCluster.url,
            username = username,
            password = password,
            tlsContext.sslContext
        )

        val finalToken = TokenModel(
            accessToken = osToken.accessToken,
            expiresAt = osToken.expiresAt,
            accountLabel = osToken.accountLabel,
            kind = AuthTokenKind.TOKEN,
            clusterApiUrl = selectedCluster.url
        )

        reporter.text("Validating cluster access...")

        val client = createValidatedApiClient(
            server,
            certAuthorityData,
            finalToken.accessToken,
            null,
            null,
            tlsContext,
            "Authentication failed: invalid OpenShift credentials."
        )

        setTokenDisplay(finalToken.accessToken)
        saveKubeconfig(selectedCluster, finalToken.accessToken, reporter)
        devSpacesContext.client = client
    }

    override fun isNextEnabled(): Boolean =
        tfUsername.text.isNotBlank() && tfPassword.password?.isNotEmpty() == true
}
