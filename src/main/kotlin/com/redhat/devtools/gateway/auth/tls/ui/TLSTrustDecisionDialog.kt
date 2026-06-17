/*
 * Copyright (c) 2025-2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.auth.tls.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.HyperlinkAdapter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.redhat.devtools.gateway.auth.tls.TlsEndpointKind
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.UIManager
import javax.swing.event.HyperlinkEvent

/**
 * Dialog that asks the user to trust a TLS certificate from a server.
 *
 * @param parent the parent component for modality; if null, dialog is centered on screen.
 * @param serverUrl The URL of the server presenting the certificate.
 * @param endpointKind The kind of TLS endpoint (server or client).
 * @param certificateInfo PEM/text representation of the certificate.
 */
class TLSTrustDecisionDialog(
    parent: Component?,
    private val serverUrl: String,
    private val endpointKind: TlsEndpointKind,
    private val certificateInfo: String
) : DialogWrapper(parent ?: JPanel(), true) {

    companion object {
        val PREFERRED_SIZE = Dimension(600, 400)
    }

    /** Will be true if user chose to persist the trust decision. */
    var rememberDecision: Boolean = false
        private set

    /** Will be true if user trusted the certificate (permanent or session). */
    var isTrusted: Boolean = false
        private set

    init {
        title = "Untrusted TLS Certificate — ${endpointKind.label}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(16, 16)).apply {
            border = JBUI.Borders.empty(JBUI.scale(8))
        }

        val wrappedUrl = serverUrl.chunked(40).joinToString("\u200B")
        val htmlText = """
            <html>
            <head>
                <style>
                    body { margin: 0; padding: 0; }
                </style>
            </head>
            <body>
                The ${endpointKind.label} at <a href="$serverUrl">$wrappedUrl</a> presents a TLS certificate that is not trusted.
                <br>
                You can choose to trust it permanently, trust it for this session only, or cancel the connection.
            </body>
            </html>
        """.trimIndent()

        val messagePane = object : JEditorPane("text/html", htmlText) {
            override fun getPreferredSize(): Dimension {
                val size = super.getPreferredSize()
                return Dimension(PREFERRED_SIZE.width, size.height)
            }
        }.apply {
            isEditable = false
            isOpaque = false
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = UIManager.getFont("Label.font")
            addHyperlinkListener(object : HyperlinkAdapter() {
                override fun hyperlinkActivated(e: HyperlinkEvent) {
                    BrowserUtil.browse(e.url)
                }
            })
        }
        panel.add(messagePane, BorderLayout.NORTH)

        val certArea = JBTextArea(certificateInfo).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = BorderFactory.createEmptyBorder()
        }

        val scrollPane = JBScrollPane(certArea).apply {
            preferredSize = PREFERRED_SIZE
            setBorder(null)
            setViewportBorder(null)
        }

        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(
            object : DialogWrapperAction("Trust Permanently") {
                override fun doAction(e: ActionEvent) {
                    isTrusted = true
                    rememberDecision = true
                    close(OK_EXIT_CODE)
                }
            },
            object : DialogWrapperAction("Trust for This Session Only") {
                override fun doAction(e: ActionEvent) {
                    isTrusted = true
                    rememberDecision = false
                    close(OK_EXIT_CODE)
                }
            },
            object : DialogWrapperAction("Cancel") {
                override fun doAction(e: ActionEvent) {
                    isTrusted = false
                    rememberDecision = false
                    close(CANCEL_EXIT_CODE)
                }
            }
        )
    }
}
