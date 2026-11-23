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
package com.redhat.devtools.gateway.recovery

import com.redhat.devtools.gateway.ThinClientJoinLink
import java.net.URI

/**
 * State of the join link a recovery episode reconnects with.
 *
 * Monotone: [CACHED] -> [REFRESH_PENDING] -> [DELIVERED_REFRESHED]. Invariant, not convenience: once
 * a rebuilt link was delivered, Guest dials stay observable for the rest of the episode, even if a
 * later failed-dial run proves that link stale again. Reverting to [CACHED] would send the cached
 * link to a pod that cannot know it, and each such forced reconnect burns the Guest's project-open retries.
 */
internal enum class LinkState {
    /** Cached link not proven stale: may be sent as-is. */
    CACHED,

    /** Cached link known stale: only a rebuilt link may be sent. */
    REFRESH_PENDING,

    /** Rebuilt link delivered: Guest dials legitimate again, worth observing. */
    DELIVERED_REFRESHED;

    /** Forced reconnect must carry a rebuilt link: cached one cannot work any more. */
    val mustRefresh: Boolean
        get() = this != CACHED

    /** This episode's forced reconnect already carried a rebuilt link. */
    val hasDeliveredRefreshed: Boolean
        get() = this == DELIVERED_REFRESHED

    /**
     * Cached link proven stale.
     *
     * Delivered stays delivered: re-requesting a refresh must not undo the fact that Guest already
     * dials a rebuilt link.
     */
    fun requestingRefresh(): LinkState =
        if (this == CACHED) {
            REFRESH_PENDING
        } else {
            this
        }
}

/**
 * Link for the forced reconnect + whether it was rebuilt. Only a rebuilt link can unblock a
 * workspace whose pod was replaced, which re-enables observing the Guest's own dials.
 */
internal class ReconnectLink(val uri: URI, val isRefreshed: Boolean)

/**
 * Link policy of one recovery watcher: which link to force a reconnect with, and when waiting for
 * the IDE server is the only option. One instance per watcher, keeps the session's current link.
 *
 * - valid link (first forced reconnect of a non-stale episode) → sent as-is, costs nothing
 * - link known stale (replaced pod, or only failed dials) → sent rebuilt only: dead one cannot
 *   succeed, and every forced reconnect makes the Guest dispose + re-open its project against a
 *   dead session, burning project-open retries (CRW-13910 live: Guest closed itself after two,
 *   exitCode 178). No fresh link yet → `null`: no forced reconnect, keep polling.
 *
 * Pure apart from the adopted link: returns link to send or `null`; consequences (log-once of a
 * missing fresh link, episode bookkeeping) belong to the caller.
 *
 * @param initialLink local join link the Guest originally dialed.
 * @param refreshLink freshly built local join link, or `null` while none can be built yet.
 */
internal class ReconnectLinkPolicy(
    initialLink: URI,
    private val refreshLink: suspend () -> URI?
) {
    /** Link the current session is known to work with; starts with connect-time link. */
    private var currentLink: URI = initialLink

    /** Last link actually delivered to the Guest; `null` before first delivery. */
    private var lastDeliveredLink: URI? = null

    /**
     * Makes [link] the session's link: refreshed link a confirmed recovery proved works. Next episode
     * starts from it instead of the connect-time link it replaced.
     */
    fun adopt(link: URI) {
        currentLink = link
    }

    /** Records link Guest actually received → identical later delivery gets a nonce. */
    fun markDelivered(link: URI) {
        lastDeliveredLink = link
    }

    /**
     * Link for the forced reconnect, or `null` when the Guest must not be forced yet.
     *
     * @param linkState cached link stale? Only a rebuilt link may be sent then.
     * @param attempt upcoming forced reconnect attempt number, used for the reconnect nonce.
     */
    suspend fun linkFor(linkState: LinkState, attempt: Int): ReconnectLink? {
        val link = if (linkState.mustRefresh) {
            freshLink()
        } else {
            ReconnectLink(currentLink, isRefreshed = false)
        }
        return link?.let { ReconnectLink(withNonceIfNeeded(it.uri, attempt), it.isRefreshed) }
    }

    /** Rebuilt link, or `null` while IDE server cannot report one yet. */
    private suspend fun freshLink(): ReconnectLink? =
        refreshLink()?.let { ReconnectLink(it, isRefreshed = true) }

    /**
     * Defeats Guest's same-link no-op guard: every forced reconnect after the first carries a nonce,
     * plus a first delivery repeating the link Guest already has (adopted link from earlier episode).
     */
    private fun withNonceIfNeeded(link: URI, attempt: Int): URI =
        if (attempt == 1 && link != lastDeliveredLink) {
            link
        } else {
            ThinClientJoinLink.withReconnectNonce(link, attempt.toLong())
        }
}
