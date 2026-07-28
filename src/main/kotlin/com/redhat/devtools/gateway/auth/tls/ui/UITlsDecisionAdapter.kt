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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.auth.tls.PemUtils
import com.redhat.devtools.gateway.auth.tls.TlsServerCertificateInfo
import com.redhat.devtools.gateway.auth.tls.TlsTrustDecision
import java.awt.Component
import javax.swing.SwingUtilities

object UITlsDecisionAdapter {

    /**
     * @param parent optional parent Component for the trust dialog; if null, the dialog is
     *               centered on screen (no parent). The caller is responsible for ensuring
     *               the value is safe to use on the EDT (e.g. captured on the EDT before a
     *               background thread runs).
     */
    fun decide(info: TlsServerCertificateInfo, parent: Component? = null): TlsTrustDecision {
        val dialogParent = parent?.let { SwingUtilities.getWindowAncestor(it) }
        thisLogger().info(
            "TLS trust: showing trust dialog for ${info.endpointKind.label} at ${info.serverUrl} " +
                "(parent=${parent?.javaClass?.simpleName ?: "none"}, " +
                "window=${dialogParent?.javaClass?.simpleName ?: "none"})"
        )

        lateinit var dialog: TlsTrustDecisionDialog
        ApplicationManager.getApplication().invokeAndWait(
            {
                dialog = TlsTrustDecisionDialog(
                    parent = dialogParent,
                    serverUrl = info.serverUrl,
                    endpointKind = info.endpointKind,
                    certificateInfo = PemUtils.toPem(info.certificateChain.first()),
                )
                dialog.show()
            },
            ModalityState.any(),
        )

        thisLogger().info("TLS trust: trust dialog closed for ${info.serverUrl}")

        return when {
            !dialog.isTrusted -> {
                thisLogger().info("TLS trust: user cancelled dialog for ${info.serverUrl}")
                TlsTrustDecision.reject()
            }

            dialog.isPermanent -> {
                thisLogger().info("TLS trust: user chose permanent trust for ${info.serverUrl}")
                TlsTrustDecision.permanent()
            }

            else -> {
                thisLogger().info("TLS trust: user chose session-only trust for ${info.serverUrl}")
                TlsTrustDecision.sessionOnly()
            }
        }
    }
}
