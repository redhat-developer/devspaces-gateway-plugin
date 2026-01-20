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

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Dialog that asks the user to trust a TLS certificate from a server.
 *
 * @param serverUrl The URL of the server presenting the certificate.
 * @param certificateInfo PEM/text representation of the certificate.
 */
class TLSTrustDecisionHandler(
    private val serverUrl: String,
    private val certificateInfo: String
) : DialogWrapper(true) {

    /** Will be true if user chose to persist the trust decision. */
    var rememberDecision: Boolean = false
        private set

    /** Will be true if user trusted the certificate (permanent or session). */
    var isTrusted: Boolean = false
        private set

    init {
        title = "Untrusted TLS Certificate"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))

        val message = JBTextArea(
            "The server at $serverUrl presents a TLS certificate that is not trusted.\n" +
                    "You can choose to trust it permanently, trust it for this session only, or cancel the connection."
        )
        message.isEditable = false
        message.isOpaque = false
        message.lineWrap = true
        message.wrapStyleWord = true

        val certArea = JBTextArea(certificateInfo).apply {
            isEditable = false
            lineWrap = false
            font = message.font
        }

        val scrollPane = JScrollPane(certArea).apply {
            preferredSize = Dimension(600, 200)
        }

        panel.add(message, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(
            object : DialogWrapperAction("Trust permanently") {
                override fun doAction(e: ActionEvent) {
                    isTrusted = true
                    rememberDecision = true
                    close(OK_EXIT_CODE)
                }
            },
            object : DialogWrapperAction("Trust for this session only") {
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
