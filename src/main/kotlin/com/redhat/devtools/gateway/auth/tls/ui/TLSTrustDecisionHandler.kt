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
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.redhat.devtools.gateway.auth.tls.TlsEndpointKind
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dialog that asks the user to trust a TLS certificate from a server.
 *
 * @param serverUrl The URL of the server presenting the certificate.
 * @param certificateInfo PEM/text representation of the certificate.
 */
class TLSTrustDecisionHandler(
    parent: Component,
    private val serverUrl: String,
    private val endpointKind: TlsEndpointKind,
    private val certificateInfo: String
) : DialogWrapper(parent, true) {

    companion object {
        val PREFERRED_SIZE = Dimension(500, 400)
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
        val panel = JPanel(BorderLayout(8, 8))

        val message = panel {
            row {
                cell(
                    JPanel(VerticalFlowLayout(
                            VerticalFlowLayout.LEFT,
                            0,
                            JBUI.scale(4),
                            true,
                            false
                    )).apply {
                        isOpaque = false
                        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                                isOpaque = false
                                add(JBLabel("The ${endpointKind.label} at "))
                                add(HyperlinkLabel(serverUrl).apply { setHyperlinkTarget(serverUrl) })
                                add(JBTextArea("presents a TLS certificate that is not trusted."))
                            }
                        )
                        add(
                            JBTextArea(
                                "You can choose to trust it permanently, trust it for this session only, or cancel the connection."
                            )
                        )
                    }
                ).align(AlignX.FILL)
            }.topGap(TopGap.MEDIUM).bottomGap(BottomGap.MEDIUM)
        }

        val certArea = JBTextArea(certificateInfo).apply {
            isEditable = false
            lineWrap = false
            //font = JBLabel().font
            border = BorderFactory.createEmptyBorder()
        }

        val scrollPane = JBScrollPane(certArea).apply {
            preferredSize = PREFERRED_SIZE
            setBorder(JBUI.Borders.empty())
            setViewportBorder(null)
        }

        panel.add(message, BorderLayout.NORTH)
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
