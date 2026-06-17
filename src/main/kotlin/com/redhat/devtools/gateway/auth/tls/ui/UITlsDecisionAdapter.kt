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
import com.redhat.devtools.gateway.auth.tls.*
import java.awt.Component
import javax.swing.JPanel

object UITlsDecisionAdapter {

    private val logger = thisLogger()

    /**
     * @param parent optional parent Component for the trust dialog; if null, the dialog is
     *               centered on screen (no parent). The caller is responsible for ensuring
     *               the value is safe to use on the EDT (e.g. captured on the EDT before a
     *               background thread runs).
     */
    suspend fun decide(info: TlsServerCertificateInfo, parent: Component? = null): TlsTrustDecision {
        val resolvedParent = parent
        logger.info(
            "TLS trust: showing trust dialog for ${info.endpointKind.label} at ${info.serverUrl} " +
                "(parent=${resolvedParent?.javaClass?.simpleName ?: "none"})"
        )

        lateinit var dialog: TLSTrustDecisionHandler

        // invokeAndWait is required here: trust runs on a progress worker thread while the EDT
        // is blocked by runProcessWithProgressSynchronously. invokeLater would queue the dialog
        // on the EDT and never run it. ModalityState.any() allows the dialog above the progress UI.
        ApplicationManager.getApplication().invokeAndWait(
            {
                dialog = TLSTrustDecisionHandler(
                    parent = resolvedParent ?: JPanel(),
                    serverUrl = info.serverUrl,
                    endpointKind = info.endpointKind,
                    certificateInfo = PemUtils.toPem(info.certificateChain.first()),
                )
                dialog.show()
            },
            ModalityState.any(),
        )

        return when {
            !dialog.isTrusted -> {
                logger.info("TLS trust: user cancelled dialog for ${info.serverUrl}")
                TlsTrustDecision.reject()
            }

            dialog.rememberDecision -> {
                logger.info("TLS trust: user chose permanent trust for ${info.serverUrl}")
                TlsTrustDecision.permanent()
            }

            else -> {
                logger.info("TLS trust: user chose session-only trust for ${info.serverUrl}")
                TlsTrustDecision.sessionOnly()
            }
        }
    }
}
