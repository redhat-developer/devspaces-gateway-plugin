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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import java.net.URI

/**
 * In-flight browser OAuth login started via [AuthSessionManager.startBrowserLogin].
 */
class BrowserLogin private constructor(
    private val manager: AbstractAuthSessionManager,
    val authorizationUri: URI,
) {

    internal val result = CompletableDeferred<SSOToken>()
    internal var job: Job? = null

    suspend fun awaitResult(timeoutMs: Long): SSOToken =
        manager.awaitBrowserLoginResult(this, timeoutMs)

    suspend fun cancel() {
        manager.cancelBrowserLogin(this)
    }

    internal companion object {
        fun create(manager: AbstractAuthSessionManager, authorizationUri: URI): BrowserLogin =
            BrowserLogin(manager, authorizationUri)
    }
}
