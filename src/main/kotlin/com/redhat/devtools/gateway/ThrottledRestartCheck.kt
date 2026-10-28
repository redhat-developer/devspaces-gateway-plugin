/*
 * Copyright (c) 2026 Red Hat, Inc.
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

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Caches the workspace-restart check so polling loops can consult it frequently (e.g. twice a
 * second) without querying the Kubernetes API on every poll.
 *
 * A stale `false` only delays progress suppression, never the restart routing in
 * [WorkspacePodTracker], which queries the cluster directly when a roll is detected.
 */
internal class ThrottledRestartCheck(
    private val queryRestartInProgress: () -> Boolean,
    private val ttl: Duration = DEFAULT_TTL,
) {

    private var cached: CachedResult? = null

    /** Returns the cached restart state, re-querying at most once per [ttl]. */
    @Synchronized
    fun isRestarting(): Boolean {
        val now = System.currentTimeMillis()
        val result = cached
        if (result != null && now - result.checkedAtMillis < ttl.inWholeMilliseconds) {
            return result.isRestarting
        }
        val isRestarting = queryRestartInProgress()
        cached = CachedResult(now, isRestarting)
        return isRestarting
    }

    private data class CachedResult(val checkedAtMillis: Long, val isRestarting: Boolean)

    companion object {
        private val DEFAULT_TTL: Duration = 5.seconds
    }
}
