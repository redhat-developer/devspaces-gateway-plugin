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

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.ui.dsl.builder.panel
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.auth.code.AuthTokenKind
import com.redhat.devtools.gateway.auth.sandbox.SandboxClusterAuthProvider
import com.redhat.devtools.gateway.auth.session.AbstractAuthSessionManager
import com.redhat.devtools.gateway.auth.session.RedHatAuthSessionManager
import com.redhat.devtools.gateway.auth.tls.TlsContext
import com.redhat.devtools.gateway.openshift.Cluster
import kotlinx.coroutines.*
import javax.swing.JPanel

/**
 * Authentication strategy for Red Hat SSO (Sandbox).
 */
class RedHatSSOAuthenticationStrategy(
    tfServer: Any,
    saveKubeconfig: suspend (Cluster, String, ProgressIndicator) -> Unit,
    private val sessionManager: RedHatAuthSessionManager
) : AbstractAuthenticationStrategy(
    tfServer,
    saveKubeconfig
) {

    override fun getAuthMethod(): AuthMethod = AuthMethod.REDHAT_SSO

    override fun getTabTitle(): String =
        DevSpacesBundle.message("connector.wizard_step.openshift_connection.tab.redhat_sso")

    override fun createPanel(): JPanel = panel {
        row {
            label(DevSpacesBundle.message("connector.wizard_step.openshift_connection.text.redhat_sso_info"))
        }
    }

    override suspend fun authenticate(
        selectedCluster: Cluster,
        server: String,
        tlsContext: TlsContext,
        devSpacesContext: DevSpacesContext,
        indicator: ProgressIndicator
    ) {
        indicator.text = "Authenticating with Red Hat..."

        val login = sessionManager.startBrowserLogin(sslContext = tlsContext.sslContext)

        ApplicationManager.getApplication().invokeLater {
            BrowserUtil.browse(login.authorizationUri)
        }

        indicator.text = "Waiting for you to complete login in your browser..."
        currentCoroutineContext().ensureActive()

        supervisorScope {
            val cancelJob = launchCancelWatcher(indicator) { login.cancel() }
            try {
                val ssoToken = login.awaitResult(AbstractAuthSessionManager.LOGIN_TIMEOUT_MS)
                indicator.text = "Obtaining OpenShift access..."

                val sandboxAuth = SandboxClusterAuthProvider()
                val finalToken = sandboxAuth.authenticate(ssoToken)

                indicator.text = "Validating cluster access..."

                try {
                    val client = createValidatedApiClient(
                        server,
                        finalToken.accessToken,
                        tlsContext = tlsContext,
                        errorMessage = "Authentication failed: Red Hat SSO token is invalid or unauthorized for this cluster."
                    )

                    devSpacesContext.client = client

                    // Only persist the long-lived pipeline service-account token.
                    // Never save the Red Hat SSO/OIDC token — it is short-lived and not a cluster API credential.
                    if (finalToken.kind == AuthTokenKind.PIPELINE) {
                        saveKubeconfig(selectedCluster, finalToken.accessToken, indicator)
                    }
                } catch (e: AuthenticationException) {
                    throw AuthenticationException(
                        "${e.message}\n\nVerify that the cluster has Red Hat SSO enabled.",
                        e
                    )
                }
            } finally {
                cancelJob.cancel()
            }
        }
    }

    override fun isNextEnabled(): Boolean =
        isServerSelected()

    /**
     * Browser login always yields a new token
     */
    override fun isDirty(saved: Cluster): Boolean = true

}
