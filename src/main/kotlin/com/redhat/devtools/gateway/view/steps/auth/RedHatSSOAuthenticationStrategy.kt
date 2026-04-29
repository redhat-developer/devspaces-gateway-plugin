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
import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import kotlin.coroutines.coroutineContext
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.auth.code.AuthTokenKind
import com.redhat.devtools.gateway.auth.sandbox.SandboxClusterAuthProvider
import com.redhat.devtools.gateway.auth.session.LOGIN_TIMEOUT_MS
import com.redhat.devtools.gateway.auth.session.RedHatAuthSessionManager
import com.redhat.devtools.gateway.auth.tls.TlsContext
import com.redhat.devtools.gateway.openshift.Cluster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.swing.JPanel

/**
 * Authentication strategy for Red Hat SSO (Sandbox).
 */
@Suppress("UnstableApiUsage")
class RedHatSSOAuthenticationStrategy(
    tfServer: Any,
    saveKubeconfig: suspend (Cluster, String, RawProgressReporter) -> Unit,
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
        row {
            label(
                DevSpacesBundle.message("connector.wizard_step.openshift_connection.text.redhat_sso_token_note")
            ).comment(
                DevSpacesBundle.message("connector.wizard_step.openshift_connection.text.pipeline_token_comment")
            )
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
        reporter.text("Authenticating with Red Hat...")

        val uri = sessionManager.startLogin(sslContext = tlsContext.sslContext)
        withContext(Dispatchers.Main) {
            BrowserUtil.browse(uri)
        }

        reporter.text("Waiting for you to complete login in your browser...")
        currentCoroutineContext().ensureActive()

        val ssoToken = sessionManager.awaitLoginResult(LOGIN_TIMEOUT_MS)
        reporter.text("Obtaining OpenShift access...")

        val sandboxAuth = SandboxClusterAuthProvider()
        val finalToken = sandboxAuth.authenticate(ssoToken)

        reporter.text("Validating cluster access...")

        try {
            val client = createValidatedApiClient(
                server, certAuthorityData,
                finalToken.accessToken,
                null,
                null,
                tlsContext,
                "Authentication failed: Red Hat SSO token is invalid or unauthorized for this cluster."
            )

            // Do not save SSO tokens
            if (finalToken.kind == AuthTokenKind.PIPELINE) {
                saveKubeconfig(selectedCluster, finalToken.accessToken, reporter)
            }
            devSpacesContext.client = client
        } catch (e: AuthenticationException) {
            throw AuthenticationException("${e.message}\n\nVerify that the cluster has Red Hat SSO enabled.", e)
        }
    }

    override fun isNextEnabled(): Boolean =
        isServerSelected()
}
