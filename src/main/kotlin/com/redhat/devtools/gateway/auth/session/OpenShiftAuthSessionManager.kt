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
import com.redhat.devtools.gateway.auth.code.JBPasswordSafeTokenStorage
import com.redhat.devtools.gateway.auth.code.OpenShiftAuthCodeFlow
import com.redhat.devtools.gateway.auth.code.SSOToken
import com.redhat.devtools.gateway.auth.code.SecureTokenStorage
import com.redhat.devtools.gateway.auth.config.AuthType
import com.redhat.devtools.gateway.auth.server.CallbackServer
import com.redhat.devtools.gateway.auth.server.OAuthCallbackServer
import com.redhat.devtools.gateway.auth.server.Parameters
import com.redhat.devtools.gateway.auth.server.RedirectUrlBuilder
import com.redhat.devtools.gateway.auth.server.ServerConfigProvider
import kotlinx.coroutines.*
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

const val OPENSHIFT_LOGIN_TIMEOUT_MS = 2 * 60_000L

@Service(Service.Level.APP)
class OpenShiftAuthSessionManager : AuthSessionManager {

    private val tokenStorage: SecureTokenStorage = JBPasswordSafeTokenStorage()

    private val serverConfig = runBlocking {
        ServerConfigProvider.getServerConfig(AuthType.SSO_OPENSHIFT) // or another type if you distinguish
    }

    private val callbackServer: CallbackServer = OAuthCallbackServer(serverConfig)

    private lateinit var authFlow: OpenShiftAuthCodeFlow

    private val listeners = mutableSetOf<AuthSessionListener>()
    private var currentToken: SSOToken? = null
    private val loginInProgress = AtomicBoolean(false)
    private var pendingLogin: CompletableDeferred<SSOToken>? = null

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

    override suspend fun initialize() {
        notifyChanged()
    }

    suspend fun awaitLoginResult(timeoutMs: Long): SSOToken {
        val deferred = pendingLogin ?: throw IllegalStateException("Login was not started")
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            throw SsoLoginException.Timeout
        }
    }

    override suspend fun startLogin(apiServerUrl: String?): URI {
        if (apiServerUrl == null) {
            throw IllegalStateException("Provide API Server URL")
        }

        if (!loginInProgress.compareAndSet(false, true)) {
            throw IllegalStateException("Login already in progress")
        }

        pendingLogin = CompletableDeferred()
        try {
            notifyChanged()

            callbackServer.stop()
            val port = callbackServer.start()

            authFlow = OpenShiftAuthCodeFlow(
                apiServerUrl,
                redirectUri = RedirectUrlBuilder.callbackUrl(serverConfig, port)
            )

            val request = authFlow.startAuthFlow()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val params: Parameters? = callbackServer.awaitCallback(OPENSHIFT_LOGIN_TIMEOUT_MS)
                    if (params == null) {
                        pendingLogin?.completeExceptionally(SsoLoginException.Timeout)
                        notifyLoginCancelled()
                        return@launch
                    }

                    val token: SSOToken = authFlow.handleCallback(params)
                    currentToken = token
                    pendingLogin?.complete(token)

                } catch (e: Exception) {
                    pendingLogin?.completeExceptionally(
                        SsoLoginException.Failed(e.message ?: "OpenShift login failed")
                    )
                } finally {
                    pendingLogin = null
                    cancelLogin()
                }
            }

            return request.authorizationUri
        } catch (e: Exception) {
            pendingLogin?.completeExceptionally(e)
            pendingLogin = null
            cancelLogin()
            throw e
        }
    }

    private suspend fun cancelLogin() {
        loginInProgress.set(false)
        notifyChanged()
        callbackServer.stop()
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

    override suspend fun getValidToken(): SSOToken? {
        val token = currentToken ?: return null
        if (!token.isExpired()) return token

        logout()
        return null
    }

    override suspend fun logout() {
        currentToken = null
        tokenStorage.clearToken()
        notifyChanged()
    }

    override fun isLoggedIn(): Boolean = currentToken != null

    override fun currentAccount(): String? = currentToken?.accountLabel
}
