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
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.redhat.devtools.gateway.auth.session

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.auth.code.RedHatAuthCodeFlow
import com.redhat.devtools.gateway.auth.code.SSOToken
import com.redhat.devtools.gateway.auth.config.AuthConfig
import com.redhat.devtools.gateway.auth.config.AuthType
import com.redhat.devtools.gateway.auth.oidc.OidcProviderMetadataResolver
import com.redhat.devtools.gateway.auth.server.CallbackServer
import com.redhat.devtools.gateway.auth.server.OAuthCallbackServer
import com.redhat.devtools.gateway.auth.server.RedirectUrlBuilder
import com.redhat.devtools.gateway.auth.server.ServerConfigProvider
import kotlinx.coroutines.runBlocking
import javax.net.ssl.SSLContext

@Service(Service.Level.APP)
class RedHatAuthSessionManager : AbstractAuthSessionManager() {

    private val serverConfig = runBlocking {
        ServerConfigProvider.getServerConfig(AuthType.SSO_REDHAT)
    }

    override val callbackServer: CallbackServer = OAuthCallbackServer(serverConfig)

    private val authConfig = AuthConfig()

    private val metadataResolver = OidcProviderMetadataResolver(authConfig.authUrl)

    private lateinit var authFlow: RedHatAuthCodeFlow

    override suspend fun startBrowserLogin(apiServerUrl: String?, sslContext: SSLContext): BrowserLogin =
        withBrowserLoginLock {
            endActiveLogin()
            var login: BrowserLogin? = null
            try {
                callbackServer.stop()
                val port = callbackServer.start()
                thisLogger().debug("Callback server started on port: $port")

                authFlow = RedHatAuthCodeFlow(
                    clientId = authConfig.clientId,
                    redirectUri = RedirectUrlBuilder.callbackUrl(serverConfig, port),
                    providerMetadata = metadataResolver.resolve()
                )

                val request = authFlow.startAuthFlow()
                thisLogger().info("Starting Red Hat SSO login, authorization URI: ${request.authorizationUri}")

                login = createBrowserLogin(request.authorizationUri)
                launchCallbackHandler(
                    login = login,
                    callbackTimeoutMs = LOGIN_TIMEOUT_MS,
                    onCallbackTimeout = ::notifyLoginCancelled,
                ) { params ->
                    val token = authFlow.handleCallback(params)
                    thisLogger().info("Red Hat SSO login successful for account: ${token.accountLabel}")
                    token
                }

                login
            } catch (e: Exception) {
                thisLogger().error("Failed to start Red Hat SSO login", e)
                login?.let { failBrowserLoginStart(it, e) }
                throw e
            }
        }

    override suspend fun loginWithCredentials(
        apiServerUrl: String,
        username: String,
        password: String,
        sslContext: SSLContext
    ): SSOToken {
        thisLogger().warn("Credential login not supported for Red Hat SSO")
        error("Not supported")
    }

    private fun notifyLoginCancelled() {
        Notifications.Bus.notify(
            Notification(
                "RedHat Authentication",
                "Login cancelled",
                "You closed the browser or the login timed out.",
                NotificationType.INFORMATION
            )
        )
    }
}
