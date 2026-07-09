/*
 * Copyright (c) 2026 Red Hat, Inc.
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
import com.redhat.devtools.gateway.auth.tls.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object UiTlsDecisionAdapter {

    suspend fun decide(info: TlsServerCertificateInfo): TlsTrustDecision =
        suspendCancellableCoroutine { continuation ->
            ApplicationManager.getApplication().invokeLater {
                if (!continuation.isActive) {
                    return@invokeLater
                }

                val dialog = TLSTrustDecisionHandler(
                    serverUrl = info.serverUrl,
                    certificateInfo = PemUtils.toPem(info.certificateChain.first())
                )
                dialog.show()

                if (!continuation.isActive) {
                    return@invokeLater
                }

                continuation.resume(
                    when {
                        !dialog.isTrusted ->
                            TlsTrustDecision.reject()

                        dialog.rememberDecision ->
                            TlsTrustDecision.permanent()

                        else ->
                            TlsTrustDecision.sessionOnly()
                    }
                )
            }
        }
}
