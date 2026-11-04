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
package com.redhat.devtools.gateway

import com.intellij.openapi.diagnostic.thisLogger
import com.jetbrains.gateway.thinClientLink.ThinClientHandle

/**
 * Brings the JetBrains Client window to the front so it repaints its connection status.
 *
 * Throttled: a forward that keeps ending and re-establishing (pod flapping) must not pull the
 * window to the front on every cut.
 *
 * State is per connect attempt: call [resetThrottle] when a connection is reused so the new
 * session can focus at once instead of waiting out the previous session's throttle.
 */
class ClientWindowFocus(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS
) {

    companion object {
        private const val DEFAULT_INTERVAL_MILLIS: Long = 5_000
    }

    /** When the Guest window was last asked to come to the front; guarded by [requestFocus]. */
    private var lastRequestMillis: Long? = null

    /**
     * Requests window focus unless the previous request was made less than [intervalMillis] ago.
     *
     * [ThinClientHandle.focusClientWindow] only sends a message over the thin-client link, so it
     * runs synchronously on the caller's thread. Deferring it to the EDT loses the request when
     * the socket close that triggered it also tears the client down before the queued action runs.
     *
     * Failures are logged and swallowed: a client that can no longer be focused must not fail the
     * connection callback.
     *
     * @param thinClient the client to focus
     */
    @Suppress("UnstableApiUsage")
    @Synchronized
    fun requestFocus(thinClient: ThinClientHandle) {
        val previous = lastRequestMillis
        val now = nowMillis()
        if (previous != null
            && now - previous < intervalMillis) {
            return
        }
        lastRequestMillis = now
        runCatching { thinClient.focusClientWindow() }
            .onFailure { e -> thisLogger().debug("Could not focus the JetBrains Client window", e) }
    }

    /** Allows the next connect attempt to focus immediately. */
    @Synchronized
    fun resetThrottle() {
        lastRequestMillis = null
    }
}
