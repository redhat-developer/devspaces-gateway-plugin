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
import com.redhat.devtools.gateway.auth.code.TokenModel
import com.redhat.devtools.gateway.auth.session.LOGIN_TIMEOUT_MS
import com.redhat.devtools.gateway.auth.session.OpenShiftAuthSessionManager
import com.redhat.devtools.gateway.auth.tls.TlsContext
import com.redhat.devtools.gateway.openshift.Cluster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.swing.JPanel

/**
 * Authentication strategy for OpenShift OAuth (browser-based PKCE).
 */
@Suppress("UnstableApiUsage")
class OpenShiftOAuthAuthenticationStrategy(
    tfServer: Any,
    saveKubeconfig: suspend (Cluster, String, RawProgressReporter) -> Unit,
    private val setTokenDisplay: suspend (String) -> Unit
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
        certAuthorityData: String,
        tlsContext: TlsContext,
        reporter: RawProgressReporter,
        devSpacesContext: DevSpacesContext
    ) {
        reporter.text("Authenticating with Openshift...")

        val openshiftSSessionManager = OpenShiftAuthSessionManager()
        val uri = openshiftSSessionManager.startLogin(
            selectedCluster.url,
            tlsContext.sslContext
        )
        withContext(Dispatchers.Main) {
            BrowserUtil.browse(uri)
        }

        reporter.text("Waiting for you to complete login in your browser...")
        currentCoroutineContext().ensureActive()

        reporter.text("Obtaining OpenShift access...")
        val osToken = openshiftSSessionManager.awaitLoginResult(LOGIN_TIMEOUT_MS)

        val finalToken = TokenModel(
            accessToken = osToken.accessToken,
            expiresAt = osToken.expiresAt,
            accountLabel = osToken.accountLabel,
            kind = AuthTokenKind.TOKEN,
            clusterApiUrl = selectedCluster.url
        )

        reporter.text("Validating cluster access...")

        val client = createValidatedApiClient(
            server,
            certAuthorityData,
            finalToken.accessToken,
            null,
            null,
            tlsContext,
            "Authentication failed: token received from OpenShift Authenticator is invalid or expired."
        )

        setTokenDisplay(finalToken.accessToken)
        saveKubeconfig(selectedCluster, finalToken.accessToken, reporter)
        devSpacesContext.client = client
    }

    override fun isNextEnabled(): Boolean =
        isServerSelected()
}
