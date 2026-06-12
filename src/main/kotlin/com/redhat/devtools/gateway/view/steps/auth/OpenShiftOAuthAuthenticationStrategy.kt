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
import com.redhat.devtools.gateway.auth.code.TokenModel
import com.redhat.devtools.gateway.auth.session.AbstractAuthSessionManager
import com.redhat.devtools.gateway.auth.session.OpenShiftAuthSessionManager
import com.redhat.devtools.gateway.auth.tls.TlsContext
import com.redhat.devtools.gateway.openshift.Cluster
import kotlinx.coroutines.*
import javax.swing.JPanel

/**
 * Authentication strategy for OpenShift OAuth (browser-based PKCE).
 */
class OpenShiftOAuthAuthenticationStrategy(
    tfServer: Any,
    saveKubeconfig: suspend (Cluster, String, ProgressIndicator) -> Unit,
    private val setTokenDisplay: (String) -> Unit,
) : AbstractAuthenticationStrategy(
    tfServer,
    saveKubeconfig
) {

    override fun getAuthMethod(): AuthMethod = AuthMethod.OPENSHIFT

    override fun getTabTitle(): String =
        DevSpacesBundle.message("connector.wizard_step.openshift_connection.tab.openshift_oauth")

    override fun createPanel(): JPanel = panel {
        row {
            label(DevSpacesBundle.message("connector.wizard_step.openshift_connection.text.openshift_oauth_info"))
        }
    }

    override suspend fun authenticate(
        selectedCluster: Cluster,
        server: String,
        certAuthority: String?,
        tlsContext: TlsContext,
        devSpacesContext: DevSpacesContext,
        indicator: ProgressIndicator
    ) {
        val sessionManager = OpenShiftAuthSessionManager()
        val login = sessionManager.startBrowserLogin(
            selectedCluster.url,
            tlsContext.sslContext,
        )

        ApplicationManager.getApplication().invokeLater {
            BrowserUtil.browse(login.authorizationUri)
        }

        indicator.text = "Waiting for you to complete login in your browser..."
        currentCoroutineContext().ensureActive()

        supervisorScope {
            val cancelJob = launchCancelWatcher(indicator) { login.cancel() }
            try {
                indicator.text = "Obtaining OpenShift access..."
                val osToken = login.awaitResult(AbstractAuthSessionManager.LOGIN_TIMEOUT_MS)

                val finalToken = TokenModel(
                    accessToken = osToken.accessToken,
                    expiresAt = osToken.expiresAt,
                    accountLabel = osToken.accountLabel,
                    kind = AuthTokenKind.TOKEN,
                    clusterApiUrl = selectedCluster.url
                )

                indicator.text = "Finishing connection..."
                val client = createTokenApiClient(server, finalToken.accessToken, tlsContext)
                devSpacesContext.client = client
                setTokenDisplay(finalToken.accessToken)
                saveKubeconfig(selectedCluster, finalToken.accessToken, indicator)
            } finally {
                cancelJob.cancel()
            }
        }
    }

    override fun isNextEnabled(): Boolean =
        isServerSelected()

    /**
     * Browser login always yields a new token; there is no pre-login field to diff against kubeconfig.
     */
    override fun isDirty(saved: Cluster): Boolean = true
}
