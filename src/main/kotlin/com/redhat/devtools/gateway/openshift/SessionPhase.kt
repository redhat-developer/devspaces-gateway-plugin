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
package com.redhat.devtools.gateway.openshift

import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Min dial survival time to count as an established RD session.
 *
 * - stale link: Guest transport-creation timeout 6 s (6.5–6.9 s measured, CRW-13910)
 * - real session: minutes
 * - 15 s > 2x failed-dial lifetime
 */
internal const val DIAL_CONFIRMATION_MS: Long = 15_000

/**
 * Phase state of one forward session: shared by handle ([PortForwardSession]) + per-connection retry
 * loops.
 *
 * - phase lives on session, not policy: same [ForwardRetryPolicy] fails fast in wizard (connect-time),
 *   retries once Guest up
 * - outcomes = survival, not bytes: stale-link dial is copied seconds before drop → neither copying nor
 *   a long last dial proves a session; only survival > [confirmationMs] does
 * - dials stalled by an outage excluded from failed-dial evidence: socket accepted before connectivity
 *   returned → handshake time says nothing; early drop = outage settling, not stale link
 * - which side ended an early dial is NOT evidence: a restarted IDE server ends a stale-token dial
 *   itself (pod side, CRW-13910 live regression 2026-11-26), the Guest's transport timeout ends it
 *   client side — both are the stale-link signature, so both count
 */
internal class SessionPhase(
    /** Min survival to count as established; overridable for tests (both sides of threshold). */
    private val confirmationMs: Long = DIAL_CONFIRMATION_MS,
    /** Clock for ended-dial classification; overridable for tests. */
    private val nowNanos: () -> Long = System::nanoTime
) {
    private val established = AtomicBoolean(false)
    private val failedDialCount = AtomicLong(0)

    /**
     * Accepted client dials not ended yet: dial visible from Guest's TCP accept, before handshake.
     *
     * Keyed by the accepted socket, so each connection owns a distinct registration: a late end or
     * a reset can only remove the registrations it is responsible for, never a newer dial's.
     */
    private val clientDials = ConcurrentHashMap.newKeySet<Socket>()

    /**
     * Handshakes of connections currently copied, count per timestamp.
     *
     * Keyed by handshake [System.nanoTime] (all the phase sees), count keeps same-nanosecond
     * connections distinct: removing one must not hide the other → watcher keeps oldest pending
     * handshake, no forced reconnect of an establishing session.
     */
    private val activeHandshakes = ConcurrentHashMap<Long, Int>()

    /**
     * Active handshakes of dials whose socket waited ≥ confirmation window for its handshake, count per timestamp.
     *
     * Queued through outage: socket accepted before connectivity returned, forwarder retrying meanwhile.
     * Handshake proves nothing about link freshness; Guest drop after = outage settling.
     * Counting it as failed dial made a 5-min network cut look stale → project-restarting forced reconnect
     * (CRW-13910 live log, 2026-11-25).
     */
    private val stalledHandshakes = ConcurrentHashMap<Long, Int>()

    @Volatile
    private var lastHandshakeNanos: Long? = null

    @Volatile
    private var lastFailedNanos: Long? = null

    @Volatile
    private var lastLongLivedNanos: Long? = null

    /** Guest up for this session; false until [markEstablished]. */
    val isEstablished: Boolean
        get() = established.get()

    /**
     * Guest has a dial open now: accepted socket, handshake or not.
     *
     * Pre-handshake matters to watcher: a dial connecting through forwarder ≠ "Guest gave up".
     */
    val isClientDialed: Boolean
        get() = clientDials.isNotEmpty()

    /**
     * A handshook connection is still being copied.
     *
     * Not recovery evidence (see class KDoc); watcher corroborates with [oldestActiveHandshakeNanos].
     */
    val hasActiveForward: Boolean
        get() = activeHandshakes.isNotEmpty()

    /** [System.nanoTime] of last successful handshake; null if none yet. */
    val lastSuccessfulHandshakeNanos: Long?
        get() = lastHandshakeNanos

    /**
     * Earliest handshake among connections still copied; null if none.
     *
     * Lets a not-yet-ended connection confirm: handshake older than [confirmationMs] survived long
     * enough → established session, not a dial about to be dropped.
     */
    val oldestActiveHandshakeNanos: Long?
        get() = activeHandshakes.keys.minOrNull()

    /** [System.nanoTime] when the last failed dial ended; see [DIAL_CONFIRMATION_MS]. */
    val lastFailedDialNanos: Long?
        get() = lastFailedNanos

    /** [System.nanoTime] when the last long-lived dial ended; see [DIAL_CONFIRMATION_MS]. */
    val lastLongLivedDialNanos: Long?
        get() = lastLongLivedNanos

    /**
     * Monotone failed-dial count since forward start. Watcher compares against count at last forced
     * reconnect: timestamp alone can't separate dials when several end between samples.
     */
    val failedDials: Long
        get() = failedDialCount.get()

    /** Idempotent: phase only moves connect-time → steady-state. */
    fun markEstablished() {
        established.set(true)
    }

    /** Records *when* handshake succeeded: caller keeps same value per connection. */
    fun recordSuccessfulHandshake(atNanos: Long) {
        lastHandshakeNanos = atNanos
    }

    /** Client socket accepted: dial exists from now, handshake pending. */
    fun recordClientDial(clientSocket: Socket) {
        clientDials.add(clientSocket)
    }

    /**
     * Dial of accepted socket over: handshook or not.
     *
     * Removes only [clientSocket]'s registration, so a handler that ends after a reset cannot
     * remove a newer dial.
     */
    fun recordClientDialEnded(clientSocket: Socket) {
        clientDials.remove(clientSocket)
    }

    /**
     * Listener closed: no dial in flight any more.
     *
     * Invalidates only the registrations present at the reset; a dial registered concurrently
     * survives, so a stale handler cannot clear a newer dial.
     */
    fun clearClientDials() {
        clientDials.removeAll(clientDials.toList())
    }

    /** Handshake succeeded → copied from [handshakeNanos] on. */
    fun recordActiveForward(handshakeNanos: Long, acceptedAtNanos: Long) {
        activeHandshakes.merge(handshakeNanos, 1, Int::plus)
        val stalledThresholdNanos = confirmationMs * NANOS_PER_MILLI
        if (handshakeNanos - acceptedAtNanos >= stalledThresholdNanos) {
            stalledHandshakes.merge(handshakeNanos, 1, Int::plus)
        }
    }

    /**
     * Connection ended. [handshakeNanos] = *this* connection's handshake: `null` when it never
     * handshook → never counted as active forward, not classified.
     *
     * Classification = survival: end within [confirmationMs] of handshake → failed (stale-link dial
     * dropped); later → real session ended by server. Stalled-handshake dial (queued before
     * connectivity returned) is not stale-link evidence, however soon it ends.
     *
     * Which side ended the dial does not matter: against a stale link the restarted IDE server ends
     * the dial itself (pod side) while the Guest's transport timeout ends it client side — both are
     * the stale-link signature. Excluding pod-side ends (CRW-13910 live regression 2026-11-26) left
     * the same-pod server restart without any stale-link evidence, so the watcher kept re-dialing
     * the dead link until its budget ran out.
     */
    fun recordEndedForward(handshakeNanos: Long?) {
        if (handshakeNanos == null) {
            return
        }
        activeHandshakes.computeIfPresent(handshakeNanos) { _, count ->
            (count - 1).takeIf { it > 0 }
        }
        val stalled = endedStalledHandshake(handshakeNanos)
        val endNanos = nowNanos()
        if (endNanos - handshakeNanos < confirmationMs * NANOS_PER_MILLI) {
            if (!stalled) {
                failedDialCount.incrementAndGet()
                lastFailedNanos = endNanos
            }
        } else {
            lastLongLivedNanos = endNanos
        }
    }

    /** Consumes one stalled entry for [handshakeNanos]; true if stalled. */
    private fun endedStalledHandshake(handshakeNanos: Long): Boolean {
        var stalled = false
        stalledHandshakes.computeIfPresent(handshakeNanos) { _, count ->
            stalled = true
            (count - 1).takeIf { it > 0 }
        }
        return stalled
    }
}

/**
 * Guest's dial outcomes on one forward session, as seen by recovery watcher.
 *
 * Narrower than [PortForwardSession], deliberately: only facts that confirm a session or prove a
 * stale link. "A connection is being copied" is not one — stale-link dial is copied seconds before
 * Guest drops it → watcher can't mistake it for recovery.
 */
internal interface ForwardDials {
    /**
     * Guest has a dial open now: accepted socket, handshake or not.
     *
     * Dial ≠ session evidence: it proves Guest has not given up, and forcing a reconnect now would
     * cut a reconnection the Guest's own loop is attempting.
     */
    val isClientDialed: Boolean

    /**
     * Earliest handshake among connections still copied; null if none. Age > [DIAL_CONFIRMATION_MS]
     * confirms an established RD session.
     */
    val oldestActiveHandshakeNanos: Long?

    /** [System.nanoTime] when last long-lived dial ended; null if none lasted long. */
    val lastLongLivedDialNanos: Long?

    /**
     * Monotone count of failed dials: a handshook dial dropped within [DIAL_CONFIRMATION_MS], by
     * either side; the watcher compares it across a forced reconnect.
     */
    val failedDials: Long
}
