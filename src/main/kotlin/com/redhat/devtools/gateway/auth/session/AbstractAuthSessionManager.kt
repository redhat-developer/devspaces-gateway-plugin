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
import com.redhat.devtools.gateway.auth.code.SSOToken
import com.redhat.devtools.gateway.auth.code.SecureTokenStorage
import com.redhat.devtools.gateway.auth.server.CallbackServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Abstract base class for authentication session managers.
 *
 * Provides common functionality for OAuth-based authentication including:
 * - Token storage and retrieval with expiration handling
 * - Login state management with concurrent access protection
 * - Session listener notifications
 * - Callback server lifecycle management
 *
 * ## Thread Safety
 * This class is designed for concurrent access:
 * - Login state is protected by [AtomicBoolean]
 * - Token access is protected by [Mutex]
 * - Listener notifications are fail-safe and copy-on-iterate
 *
 * ## Subclass Responsibilities
 * Implementations must provide:
 * - [initialize]: One-time setup on plugin startup
 * - [startLogin]: Provider-specific OAuth flow initialization
 * - [loginWithCredentials]: Provider-specific credential-based login (or throw UnsupportedOperationException)
 * - [callbackServer]: The callback server instance for OAuth flows
 *
 * @param tokenStorage Storage mechanism for persisting tokens securely. Defaults to [JBPasswordSafeTokenStorage]
 */
abstract class AbstractAuthSessionManager(
    protected val tokenStorage: SecureTokenStorage = JBPasswordSafeTokenStorage()
) : AuthSessionManager {

    protected abstract val callbackServer: CallbackServer

    private val listeners = mutableSetOf<AuthSessionListener>()
    private val tokenMutex = Mutex()
    private var _currentToken: SSOToken? = null

    protected var currentToken: SSOToken?
        get() = _currentToken
        set(value) { _currentToken = value }

    protected val loginInProgress = AtomicBoolean(false)
    protected var pendingLogin: CompletableDeferred<SSOToken>? = null

    /**
     * Checks if a login operation is currently in progress.
     *
     * @return true if login is in progress, false otherwise
     */
    fun isLoginInProgress(): Boolean = loginInProgress.get()

    /**
     * Adds a session change listener.
     * Thread-safe and can be called during listener notification.
     *
     * @param listener The listener to add
     */
    fun addListener(listener: AuthSessionListener) {
        synchronized(listeners) {
            listeners += listener
        }
    }

    /**
     * Removes a session change listener.
     * Thread-safe and can be called during listener notification.
     *
     * @param listener The listener to remove
     */
    fun removeListener(listener: AuthSessionListener) {
        synchronized(listeners) {
            listeners -= listener
        }
    }

    /**
     * Notifies all registered listeners of a session change.
     * Notification failures are logged but do not prevent other listeners from being notified.
     */
    protected fun notifyChanged() {
        val listenersCopy = synchronized(listeners) { listeners.toList() }
        listenersCopy.forEach { listener ->
            try {
                listener.sessionChanged()
            } catch (e: Exception) {
                thisLogger().error("Session listener notification failed", e)
            }
        }
    }

    /**
     * Awaits the result of a login operation started via [startLogin].
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return The received SSO token
     * @throws IllegalStateException if login was not started
     * @throws SsoLoginException.Timeout if the operation times out
     */
    override suspend fun awaitLoginResult(timeoutMs: Long): SSOToken {
        val deferred = pendingLogin ?: throw IllegalStateException("Login was not started")
        thisLogger().debug("Awaiting login result with timeout ${timeoutMs}ms")
        return try {
            val token = withTimeout(timeoutMs.milliseconds) { deferred.await() }
            thisLogger().info("Login result received successfully")
            token
        } catch (e: TimeoutCancellationException) {
            thisLogger().warn("Login timed out after ${timeoutMs}ms")
            throw SsoLoginException.Timeout
        }
    }

    /**
     * Cancels the current login operation and cleans up resources.
     */
    protected suspend fun cancelLogin() {
        thisLogger().debug("Cancelling login")
        loginInProgress.set(false)
        notifyChanged()
        callbackServer.stop()
    }

    /**
     * Returns a valid (non-expired) token or null.
     * If the current token is expired, automatically logs out and returns null.
     *
     * @return A valid token or null if not logged in or token is expired
     */
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
        // Call private logout method to avoid deadlock (already holding tokenMutex)
        doLogout()
        return null
    }

    /**
     * Logs out the current user by clearing the token from storage and memory.
     * Notifies all registered listeners of the session change.
     */
    override suspend fun logout() = tokenMutex.withLock {
        doLogout()
    }

    /**
     * Internal logout implementation that doesn't acquire the lock.
     * Must be called while holding tokenMutex.
     */
    private suspend fun doLogout() {
        val account = currentToken?.accountLabel
        thisLogger().info("Logging out${if (account != null) " account: $account" else ""}")
        currentToken = null
        tokenStorage.clearToken()
        notifyChanged()
    }

    /**
     * Checks if a user is currently logged in.
     *
     * @return true if a token exists (regardless of expiration), false otherwise
     */
    override fun isLoggedIn(): Boolean = currentToken != null

    /**
     * Returns the account label of the current token.
     *
     * @return The account label or null if not logged in
     */
    override fun currentAccount(): String? = currentToken?.accountLabel
}
