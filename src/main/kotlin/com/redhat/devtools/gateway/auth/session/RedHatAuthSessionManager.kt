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
import com.redhat.devtools.gateway.auth.code.JBPasswordSafeTokenStorage
import com.redhat.devtools.gateway.auth.code.RedHatAuthCodeFlow
import com.redhat.devtools.gateway.auth.code.SSOToken
import com.redhat.devtools.gateway.auth.code.SecureTokenStorage
import com.redhat.devtools.gateway.auth.config.AuthConfig
import com.redhat.devtools.gateway.auth.config.AuthType
import com.redhat.devtools.gateway.auth.oidc.OidcProviderMetadataResolver
import com.redhat.devtools.gateway.auth.server.CallbackServer
import com.redhat.devtools.gateway.auth.server.OAuthCallbackServer
import com.redhat.devtools.gateway.auth.server.RedirectUrlBuilder
import com.redhat.devtools.gateway.auth.server.ServerConfigProvider
import kotlinx.coroutines.*
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import kotlin.time.Duration.Companion.milliseconds

const val LOGIN_TIMEOUT_MS = 2 * 60_000L

@Service(Service.Level.APP)
class RedHatAuthSessionManager(): AuthSessionManager {

    private val tokenStorage: SecureTokenStorage =
        JBPasswordSafeTokenStorage()

    private val serverConfig = runBlocking {
        ServerConfigProvider.getServerConfig(AuthType.SSO_REDHAT)
    }

    private val callbackServer: CallbackServer = OAuthCallbackServer(serverConfig)

    private val authConfig = AuthConfig()

    private val providerMetadata = runBlocking {
        OidcProviderMetadataResolver(authConfig.authUrl).resolve()
    }

    private lateinit var authFlow: RedHatAuthCodeFlow

    private val listeners = mutableSetOf<AuthSessionListener>()
    private var currentToken: SSOToken? = null

    private val loginInProgress = AtomicBoolean(false)

    fun isLoginInProgress(): Boolean = loginInProgress.get()

    fun addListener(listener: AuthSessionListener) {
        listeners += listener
    }

    fun removeListener(listener: AuthSessionListener) {
        listeners -= listener
    }

    private fun notifyChanged() {
        listeners.forEach { it.sessionChanged() }
    }

    /**
     * Called once on plugin startup.
     */
    override suspend fun initialize() {
        thisLogger().info("RedHatAuthSessionManager initialized")
        notifyChanged()
    }

    private var pendingLogin: CompletableDeferred<SSOToken>? = null

    suspend fun awaitLoginResult(timeoutMs: Long): SSOToken {
        val deferred = pendingLogin
            ?: throw IllegalStateException("Login was not started")

        thisLogger().debug("Awaiting login result with timeout ${timeoutMs}ms")
        return try {
            val token = withTimeout(timeoutMs.milliseconds) {
                deferred.await()
            }
            thisLogger().info("Login result received successfully")
            token
        } catch (e: TimeoutCancellationException) {
            thisLogger().warn("Login timed out after ${timeoutMs}ms")
            throw SsoLoginException.Timeout
        }
    }

    /**
     * Starts the login process and returns browser URL.
     */
    override suspend fun startLogin(apiServerUrl: String?, sslContext: SSLContext): URI {
        if (!loginInProgress.compareAndSet(false, true)) {
            thisLogger().warn("Login already in progress")
            throw IllegalStateException("Login already in progress")
        }

        thisLogger().info("Starting Red Hat SSO login")
        pendingLogin = CompletableDeferred()

        try {
            notifyChanged()

            callbackServer.stop()
            val port = callbackServer.start()
            thisLogger().debug("Callback server started on port: $port")

            authFlow = RedHatAuthCodeFlow(
                clientId = authConfig.clientId,
                redirectUri = RedirectUrlBuilder.callbackUrl(serverConfig, port),
                providerMetadata = providerMetadata,
                sslContext = sslContext
            )

            val request = authFlow.startAuthFlow()
            thisLogger().debug("Auth flow started, authorization URI: ${request.authorizationUri}")

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    thisLogger().debug("Waiting for OAuth callback...")
                    val params = callbackServer.awaitCallback(LOGIN_TIMEOUT_MS)
                    if (params == null) {
                        thisLogger().warn("OAuth callback timed out or was cancelled")
                        pendingLogin?.completeExceptionally(
                            SsoLoginException.Timeout
                        )
                        notifyLoginCancelled()

                        return@launch
                    }

                    thisLogger().debug("OAuth callback received, handling...")
                    val token = authFlow.handleCallback(params)
                    currentToken = token
                    thisLogger().info("Red Hat SSO login successful for account: ${token.accountLabel}")

                    pendingLogin?.complete(token)
                } catch (e: Exception) {
                    thisLogger().error("Red Hat SSO login failed", e)
                    pendingLogin?.completeExceptionally(
                        SsoLoginException.Failed(e.message ?: "SSO login failed")
                    )
                } finally {
                    pendingLogin = null
                    cancelLogin()
                }
            }

            return request.authorizationUri
        } catch (e: Exception) {
            thisLogger().error("Failed to start Red Hat SSO login", e)
            pendingLogin?.completeExceptionally(e)
            pendingLogin = null
            cancelLogin()
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

    private suspend fun cancelLogin() {
        thisLogger().debug("Cancelling login")
        loginInProgress.set(false)
        notifyChanged()
        callbackServer.stop()
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

    /**
     * Returns a valid (non-expired) token or null.
     * Refreshes automatically if possible.
     */
    override suspend fun getValidToken(): SSOToken? {
        val token = currentToken
        if (token == null) {
            thisLogger().debug("No current token available")
            return null
        }

        if (!token.isExpired()) {
            thisLogger().debug("Returning valid token for account: ${token.accountLabel}")
            return token
        }

        thisLogger().info("Token expired for account: ${token.accountLabel}, logging out")
        logout()
        return null
    }

    override suspend fun logout() {
        val account = currentToken?.accountLabel
        thisLogger().info("Logging out${if (account != null) " account: $account" else ""}")
        currentToken = null
        tokenStorage.clearToken()
        notifyChanged()
    }

    override fun isLoggedIn(): Boolean = currentToken != null

    override fun currentAccount(): String? = currentToken?.accountLabel
}
