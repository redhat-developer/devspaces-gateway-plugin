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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.event.DocumentListener
import java.awt.event.KeyListener
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.view.ui.PasteClipboardMenu
import com.redhat.devtools.gateway.view.ui.PasswordFieldWithToggle
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.auth.tls.TlsContext
import com.redhat.devtools.gateway.openshift.Cluster
import com.redhat.devtools.gateway.util.ClipboardTokenMonitor
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Authentication strategy for token-based authentication.
 */
@Suppress("UnstableApiUsage")
class TokenAuthenticationStrategy(
    tfServer: Any,
    saveKubeconfig: suspend (Cluster, String, RawProgressReporter) -> Unit,
    private val onFieldChanged: () -> DocumentListener,
    private val createEnterKeyListener: () -> KeyListener
) : AbstractAuthenticationStrategy(
    tfServer,
    saveKubeconfig
) {

    private val tokenFieldWithToggle = PasswordFieldWithToggle().apply {
        setToggleButtonTooltip(DevSpacesBundle.message("connector.wizard_step.openshift_connection.checkbox.show_token"))
        passwordField.document.addDocumentListener(onFieldChanged())
        PasteClipboardMenu.addTo(passwordField)
        passwordField.addKeyListener(createEnterKeyListener())
    }

    val tfToken = tokenFieldWithToggle.passwordField

    val tokenSuggestionLabel = JBLabel().apply {
        text = ""
        foreground = JBColor.BLUE
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
        font = font.deriveFont(Font.ITALIC or Font.PLAIN)
    }

    private val clipboardMonitor = ClipboardTokenMonitor()
    private var lastDetectedToken: String? = null
    private var tokenLabelListener: MouseAdapter? = null

    override fun getAuthMethod(): AuthMethod = AuthMethod.TOKEN

    override fun getTabTitle(): String =
        DevSpacesBundle.message("connector.wizard_step.openshift_connection.tab.token")

    override fun createPanel(): JPanel = panel {
        row {
            cell(tokenSuggestionLabel).align(Align.FILL)
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.token")) {
            cell(tokenFieldWithToggle).resizableColumn().align(Align.FILL)
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
        reporter.text("Validating token...")

        val token = String(tfToken.password)

        val client = createValidatedApiClient(
            server,
            certAuthorityData,
            token,
            null,
            null,
            tlsContext,
            "Authentication failed: invalid server URL or token."
        )

        saveKubeconfig.invoke(selectedCluster, token, reporter)
        devSpacesContext.client = client
    }

    override fun isNextEnabled(): Boolean =
        isServerSelected()
                && tfToken.password?.isNotEmpty() == true

    /**
     * Start monitoring clipboard for tokens.
     * Should be called during initialization.
     */
    fun startMonitoring(parentComponent: JComponent) {
        clipboardMonitor.addListener { token ->
            lastDetectedToken = token
            ApplicationManager.getApplication().invokeLater(
                {
                    tokenSuggestionLabel.apply {
                        text = "Token detected in clipboard. Click here to use it."
                        isVisible = true
                        isEnabled = true
                    }
                },
                ModalityState.stateForComponent(parentComponent)
            )
        }

        tokenLabelListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                val token = lastDetectedToken ?: return
                tfToken.text = token
                tokenSuggestionLabel.isVisible = false
            }
        }
        tokenSuggestionLabel.addMouseListener(tokenLabelListener)
        tokenSuggestionLabel.isVisible = false

        clipboardMonitor.start()
        clipboardMonitor.checkNow()?.let { token ->
            lastDetectedToken = token
            tokenSuggestionLabel.apply {
                text = "Token detected in clipboard. Click here to use it."
                isVisible = true
                isEnabled = true
            }
        }
    }

    /**
     * Stop monitoring clipboard and clean up resources.
     * Should be called during disposal.
     */
    fun stopMonitoring() {
        tokenLabelListener?.let { listener ->
            tokenSuggestionLabel.removeMouseListener(listener)
        }
        clipboardMonitor.stop()
    }
}
