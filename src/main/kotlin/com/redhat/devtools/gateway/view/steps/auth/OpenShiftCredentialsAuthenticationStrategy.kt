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

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
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
import com.redhat.devtools.gateway.view.ui.PasswordFieldWithToggle
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Authentication strategy for OpenShift credentials (username/password).
 */
class OpenShiftCredentialsAuthenticationStrategy(
    tfServer: Any,
    saveKubeconfig: suspend (Cluster, String, ProgressIndicator) -> Unit,
    private val onFieldChanged: () -> DocumentListener,
    private val createEnterKeyListener: () -> KeyListener,
    private val setTokenDisplay: suspend (String) -> Unit
) : AbstractAuthenticationStrategy(
    tfServer,
    saveKubeconfig
) {

    private val tfUsername = JBTextField().apply {
        document.addDocumentListener(onFieldChanged())
        PasteClipboardMenu.addTo(this)
        addKeyListener(createEnterKeyListener())
    }

    private val tfPassword = PasswordFieldWithToggle().apply {
        setToggleButtonTooltip(DevSpacesBundle.message("connector.wizard_step.openshift_connection.checkbox.show_password"))
        passwordField.document.addDocumentListener(onFieldChanged())
        PasteClipboardMenu.addTo(passwordField)
        passwordField.addKeyListener(createEnterKeyListener())
    }

    override fun getAuthMethod(): AuthMethod = AuthMethod.OPENSHIFT_CREDENTIALS

    override fun getTabTitle(): String =
        DevSpacesBundle.message("connector.wizard_step.openshift_connection.tab.credentials")

    // not using dsl bcs resulted in password being more narrow than username
    override fun createPanel(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        // BorderLayout.NORTH: keep the form at the top of the tab; a bare GridBagLayout in a
        // tall tab cell vertically centers its grid, which looks like a large gap above username.
        add(JPanel(GridBagLayout()).apply {
            isOpaque = false
            fun addLabeledField(row: Int, labelText: String, field: JComponent) {
                val gapBelow = if (row == 0) JBUI.scale(6) else 0
                add(JBLabel(labelText), GridBagConstraints().apply {
                    gridx = 0
                    gridy = row
                    anchor = GridBagConstraints.BASELINE_TRAILING
                    insets = JBUI.insets(0, 0, gapBelow, JBUI.scale(8))
                })
                add(field, GridBagConstraints().apply {
                    gridx = 1
                    gridy = row
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.BASELINE_LEADING
                    insets = JBUI.insetsBottom(gapBelow)
                })
            }
            addLabeledField(
                0,
                DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.username"),
                tfUsername,
            )
            addLabeledField(
                1,
                DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.password"),
                tfPassword,
            )
        }, BorderLayout.NORTH)
    }

    override suspend fun authenticate(
        selectedCluster: Cluster,
        server: String,
        certAuthority: String?,
        tlsContext: TlsContext,
        indicator: ProgressIndicator,
        devSpacesContext: DevSpacesContext
    ) {
        indicator.text = "Authenticating with OpenShift credentials..."

        val username = tfUsername.text
        val password = String(tfPassword.passwordField.password)

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

        indicator.text = "Validating cluster access..."

        val client = createValidatedApiClient(
            server,
            certAuthority,
            finalToken.accessToken,
            null,
            null,
            tlsContext,
            "Authentication failed: invalid OpenShift credentials."
        )

        setTokenDisplay(finalToken.accessToken)
        saveKubeconfig(selectedCluster, finalToken.accessToken, indicator)
        devSpacesContext.client = client
    }

    override fun isNextEnabled(): Boolean =
        isServerSelected()
                && tfUsername.text.isNotBlank()
                && tfPassword.passwordField.password?.isNotEmpty() == true
}
