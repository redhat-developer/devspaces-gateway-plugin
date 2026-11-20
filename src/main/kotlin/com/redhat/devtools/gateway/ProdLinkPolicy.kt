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

import java.net.URI

/**
 * State of the join link a recovery episode prods with.
 *
 * The state only ever moves forward: [CACHED] -> [REFRESH_PENDING] -> [DELIVERED_REFRESHED]. That
 * monotonicity is a behaviour invariant, not a convenience: once a rebuilt link was delivered, the
 * Guest's dials stay observable for the rest of the episode, even if a later failed-dial run proves
 * that delivered link stale again. Reverting to [CACHED] would send the cached link to a pod that
 * cannot know it, and each such prod burns the Guest's project-open retries.
 */
internal enum class LinkState {
    /** Nothing proved the cached link stale yet: it may be prodded as-is. */
    CACHED,

    /** The cached link is known stale: only a rebuilt link may be prodded. */
    REFRESH_PENDING,

    /** A rebuilt link was delivered: the Guest's dials are legitimate again and worth observing. */
    DELIVERED_REFRESHED;

    /** Whether a prod must carry a rebuilt link: the cached one cannot work any more. */
    val mustRefresh: Boolean
        get() = this != CACHED

    /** Whether a prod of this episode already carried a rebuilt link. */
    val hasDeliveredRefreshed: Boolean
        get() = this == DELIVERED_REFRESHED

    /**
     * The cached link was proven stale.
     *
     * A delivered link stays delivered: re-requesting a refresh must not undo the fact that the
     * Guest already dials a rebuilt link.
     */
    fun requestingRefresh(): LinkState =
        if (this == CACHED) REFRESH_PENDING else this
}

/**
 * A link to prod with, and whether it was rebuilt. Only a rebuilt link can unblock a workspace
 * whose pod was replaced, which is what re-enables observing the Guest's own dials.
 */
internal class ProdLink(val uri: URI, val isRefreshed: Boolean)

/**
 * The link policy of one recovery watcher: which link to prod the Guest with, and when waiting for
 * the IDE server is the only option. One instance serves every episode of that watcher.
 *
 * A valid link (the first prod of a non-stale episode) is sent as-is: it costs nothing. A link
 * known stale - a replaced pod, or only failed dials - is only ever sent rebuilt: prodding the dead
 * one cannot succeed, and every prod makes the Guest dispose and re-open its project against a dead
 * session, which burns its project-open retries (CRW-13910 live testing: the Guest closed itself
 * after two such prods, exitCode 178). While no fresh link exists yet the answer is therefore
 * `null` - no prod, keep polling.
 *
 * The policy is pure: it returns the link to send, or `null`, and leaves every consequence - the
 * log-once of a missing fresh link, the bookkeeping of the episode - to the caller.
 *
 * @param cachedLink the local join link the Guest originally dialed.
 * @param refreshLink a freshly built local join link, or `null` while none can be built yet.
 */
internal class ProdLinkPolicy(
    private val cachedLink: URI,
    private val refreshLink: suspend () -> URI?
) {
    /**
     * The link to prod with, or `null` when the Guest must not be prodded yet.
     *
     * @param linkState whether the cached link is stale: only a rebuilt link may be prodded then.
     * @param attempt the upcoming prod attempt number, used for the reconnect nonce. The nonce is
     *   added from the second prod on, where the Guest may already have seen the previous link.
     */
    suspend fun linkFor(linkState: LinkState, attempt: Int): ProdLink? {
        val link = if (linkState.mustRefresh) {
            freshLink()
        } else {
            ProdLink(cachedLink, isRefreshed = false)
        }
        return link?.let { ProdLink(withNonceIfNeeded(it.uri, attempt), it.isRefreshed) }
    }

    /** A rebuilt link, or `null` while the IDE server cannot report one yet. */
    private suspend fun freshLink(): ProdLink? =
        refreshLink()?.let { ProdLink(it, isRefreshed = true) }

    /** Defeats the Guest's same-link no-op guard from the second prod of an episode on. */
    private fun withNonceIfNeeded(link: URI, attempt: Int): URI =
        if (attempt == 1) link else ThinClientJoinLink.withReconnectNonce(link, attempt.toLong())
}
