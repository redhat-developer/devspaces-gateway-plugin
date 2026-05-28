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

import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.auth.code.JBPasswordSafeTokenStorage
import com.redhat.devtools.gateway.auth.code.Parameters
import com.redhat.devtools.gateway.auth.code.SSOToken
import com.redhat.devtools.gateway.auth.code.SecureTokenStorage
import com.redhat.devtools.gateway.auth.server.CallbackServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

/**
 * Abstract base class for authentication session managers.
 *
 * Browser logins use a single [BrowserLogin] at a time.
 * Starting a new login or calling [BrowserLogin.cancel] ends any previous in-flight login.
 */
abstract class AbstractAuthSessionManager(
    protected val tokenStorage: SecureTokenStorage = JBPasswordSafeTokenStorage()
) : AuthSessionManager {

    protected abstract val callbackServer: CallbackServer

    companion object {
        const val LOGIN_TIMEOUT_MS = 2 * 60_000L
    }

    private val tokenMutex = Mutex()
    private val loginMutex = Mutex()
    private val loginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var _currentToken: SSOToken? = null
    protected var currentToken: SSOToken?
        get() = _currentToken
        set(value) { _currentToken = value }

    private var activeLogin: BrowserLogin? = null

    /**
     * Creates a [BrowserLogin] and registers it as active. Caller must run inside [withBrowserLoginLock].
     */
    protected fun createBrowserLogin(authorizationUri: URI): BrowserLogin {
        val login = BrowserLogin.create(this, authorizationUri)
        activeLogin = login
        return login
    }

    /**
     * Stops the current browser login, if any. Caller must run inside [withBrowserLoginLock].
     */
    protected suspend fun endActiveLogin() {
        val previous = activeLogin ?: return
        thisLogger().debug("Ending in-flight browser login")
        previous.job?.cancelAndJoin()
        previous.result.takeIf { !it.isCompleted }?.completeExceptionally(SsoLoginException.Cancelled())
        callbackServer.stop()
        activeLogin = null
    }

    internal suspend fun awaitBrowserLoginResult(login: BrowserLogin, timeoutMs: Long): SSOToken {
        check(activeLogin === login) { "Login is no longer active" }
        thisLogger().debug("Awaiting browser login result with timeout ${timeoutMs}ms")
        return try {
            val token = withTimeout(timeoutMs.milliseconds) {
                login.result.await()
            }
            thisLogger().info("Login result received successfully")
            token
        } catch (e: TimeoutCancellationException) {
            thisLogger().warn("Login timed out after ${timeoutMs}ms")
            cancelBrowserLogin(login)
            throw SsoLoginException.Timeout()
        }
    }

    internal suspend fun cancelBrowserLogin(login: BrowserLogin) = withBrowserLoginLock {
        if (activeLogin !== login) {
            thisLogger().debug("Ignoring cancel for stale browser login")
            return@withBrowserLoginLock
        }
        thisLogger().debug("Cancelling browser login")
        login.job?.cancelAndJoin()
        login.result.takeIf { !it.isCompleted }?.completeExceptionally(SsoLoginException.Cancelled())
        callbackServer.stop()
        activeLogin = null
    }

    protected fun launchCallbackHandler(
        login: BrowserLogin,
        callbackTimeoutMs: Long,
        onCallbackTimeout: () -> Unit,
        handleCallback: suspend (Parameters) -> SSOToken,
    ) {
        login.job = loginScope.launch {
            try {
                thisLogger().debug("Waiting for OAuth callback...")
                val params = callbackServer.awaitCallback(callbackTimeoutMs)
                if (params == null) {
                    if (!login.result.isCompleted) {
                        val error = if (isActive) {
                            SsoLoginException.Timeout()
                        } else {
                            SsoLoginException.Cancelled()
                        }
                        login.result.completeExceptionally(error)
                    }
                    if (isActive) {
                        thisLogger().warn("OAuth callback timed out")
                        onCallbackTimeout()
                    }
                    return@launch
                }

                thisLogger().debug("OAuth callback received, handling...")
                val token = handleCallback(params)
                currentToken = token
                completeLoginSuccess(login, token)
            } catch (e: CancellationException) {
                completeLoginCancelled(login)
                throw e
            } catch (e: Exception) {
                thisLogger().error("Browser login failed", e)
                completeLoginFailed(login, e)
            } finally {
                withBrowserLoginLock { releaseActiveLogin(login) }
            }
        }
    }

    protected fun completeLoginSuccess(login: BrowserLogin, token: SSOToken) {
        if (!login.result.isCompleted) {
            login.result.complete(token)
        }
    }

    protected fun completeLoginFailed(login: BrowserLogin, e: Exception) {
        if (login.result.isCompleted) return
        val error = when (e) {
            is SsoLoginException -> e
            else -> SsoLoginException.Failed(e.message ?: "Login failed")
        }
        login.result.completeExceptionally(error)
    }

    protected fun completeLoginCancelled(login: BrowserLogin) {
        if (!login.result.isCompleted) {
            login.result.completeExceptionally(SsoLoginException.Cancelled())
        }
    }

    /**
     * Stops the callback server and clears [activeLogin] when [login] is still current.
     * Caller must run inside [withBrowserLoginLock].
     */
    protected suspend fun releaseActiveLogin(login: BrowserLogin) {
        if (activeLogin !== login) return
        thisLogger().debug("Releasing browser login")
        callbackServer.stop()
        activeLogin = null
    }

    protected suspend fun failBrowserLoginStart(login: BrowserLogin, e: Exception) {
        completeLoginFailed(login, e)
        releaseActiveLogin(login)
    }

    protected suspend fun <T> withBrowserLoginLock(block: suspend () -> T): T =
        loginMutex.withLock { block() }

    override suspend fun getValidToken(): SSOToken? = tokenMutex.withLock {
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
        doLogout()
        return null
    }

    override suspend fun logout() = tokenMutex.withLock {
        doLogout()
    }

    private suspend fun doLogout() {
        val account = currentToken?.accountLabel
        thisLogger().info("Logging out${if (account != null) " account: $account" else ""}")
        withBrowserLoginLock { endActiveLogin() }
        currentToken = null
        tokenStorage.clearToken()
    }

    override fun isLoggedIn(): Boolean = currentToken != null

    override fun currentAccount(): String? = currentToken?.accountLabel
}
