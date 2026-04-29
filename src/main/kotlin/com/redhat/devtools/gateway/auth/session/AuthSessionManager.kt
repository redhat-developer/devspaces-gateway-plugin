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

import com.redhat.devtools.gateway.auth.code.SSOToken
import java.net.URI
import javax.net.ssl.SSLContext

interface AuthSessionManager {

    /** Called once on plugin startup to load any existing token. */
    suspend fun initialize()

    /** Starts login and returns browser URL */
    suspend fun startLogin(apiServerUrl: String? = null, sslContext: SSLContext): URI

    /** Awaits for the browser login result */
    suspend fun awaitLoginResult(timeoutMs: Long): SSOToken

    /** Starts login using the given credentials and returns a valid token */
    suspend fun loginWithCredentials(apiServerUrl: String, username: String, password: String, sslContext: SSLContext): SSOToken

    /** Returns a valid (non-expired) token or null. Refreshes automatically if possible. */
    suspend fun getValidToken(): SSOToken?

    /** Clears session and stored tokens. */
    suspend fun logout()

    /** Returns true if a session is active. */
    fun isLoggedIn(): Boolean

    /** Returns the current account label, if logged in. */
    fun currentAccount(): String?
}