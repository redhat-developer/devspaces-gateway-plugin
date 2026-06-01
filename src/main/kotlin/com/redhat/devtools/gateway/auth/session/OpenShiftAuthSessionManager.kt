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
package com.redhat.devtools.gateway.auth.session

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.auth.code.OpenShiftAuthCodeFlow
import com.redhat.devtools.gateway.auth.code.SSOToken
import com.redhat.devtools.gateway.auth.config.AuthType
import com.redhat.devtools.gateway.auth.server.CallbackServer
import com.redhat.devtools.gateway.auth.server.OAuthCallbackServer
import com.redhat.devtools.gateway.auth.server.RedirectUrlBuilder
import com.redhat.devtools.gateway.auth.server.ServerConfigProvider
import kotlinx.coroutines.runBlocking
import java.net.URI
import javax.net.ssl.SSLContext

@Service(Service.Level.APP)
class OpenShiftAuthSessionManager : AbstractAuthSessionManager() {

    private val serverConfig = runBlocking {
        ServerConfigProvider.getServerConfig(AuthType.SSO_OPENSHIFT)
    }

    override val callbackServer: CallbackServer = OAuthCallbackServer(serverConfig)

    private lateinit var authFlow: OpenShiftAuthCodeFlow

    override suspend fun startBrowserLogin(apiServerUrl: String?, sslContext: SSLContext): BrowserLogin {
        if (apiServerUrl == null) {
            thisLogger().error("API Server URL is null")
            throw IllegalStateException("Provide API Server URL")
        }

        return withBrowserLoginLock {
            endActiveLogin()
            var login: BrowserLogin? = null
            try {
                callbackServer.stop()
                val port = callbackServer.start()
                thisLogger().debug("Callback server started on port: $port")

                authFlow = OpenShiftAuthCodeFlow(
                    apiServerUrl = apiServerUrl,
                    redirectUri = RedirectUrlBuilder.callbackUrl(serverConfig, port),
                    sslContext = sslContext
                )

                val request = authFlow.startAuthFlow()
                thisLogger().info("Starting OpenShift login for API server: $apiServerUrl, authorization URI: ${request.authorizationUri}")

                login = createBrowserLogin(request.authorizationUri)
                launchCallbackHandler(
                    login = login,
                    callbackTimeoutMs = LOGIN_TIMEOUT_MS,
                    onCallbackTimeout = ::notifyLoginCancelled,
                ) { params ->
                    val token = authFlow.handleCallback(params)
                    thisLogger().info("OpenShift login successful for account: ${token.accountLabel}")
                    token
                }

                login
            } catch (e: Exception) {
                thisLogger().error("Failed to start OpenShift login", e)
                login?.let { failBrowserLoginStart(it, e) }
                throw e
            }
        }
    }

    private fun notifyLoginCancelled() {
        Notifications.Bus.notify(
            Notification(
                "OpenShift Authentication",
                "Login cancelled",
                "You closed the browser or the login timed out.",
                NotificationType.INFORMATION
            )
        )
    }

    override suspend fun loginWithCredentials(
        apiServerUrl: String,
        username: String,
        password: String,
        sslContext: SSLContext
    ): SSOToken {
        thisLogger().info("Starting OpenShift credential login for user: $username at $apiServerUrl")
        try {
            authFlow = OpenShiftAuthCodeFlow(
                apiServerUrl = apiServerUrl,
                redirectUri = URI("$apiServerUrl/oauth/token/implicit"),
                sslContext = sslContext
            )

            val token = authFlow.login(
                mapOf(
                    "username" to username,
                    "password" to password
                )
            )

            currentToken = token
            thisLogger().info("OpenShift credential login successful for account: ${token.accountLabel}")
            return token
        } catch (e: Exception) {
            thisLogger().error("OpenShift credential login failed for user: $username", e)
            throw SsoLoginException.Failed(e.message ?: "OpenShift credential login failed")
        }
    }
}
