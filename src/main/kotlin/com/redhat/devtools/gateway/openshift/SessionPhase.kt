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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * How long a dial must survive to count as an established RD session.
 *
 * A dial against a stale link is dropped by the Guest's own transport-creation timeout (6 s) —
 * measured 6.5–6.9 s in CRW-13910 live testing — while a real session lives for minutes. 15 s is
 * more than twice the failed-dial lifetime.
 */
internal const val DIAL_CONFIRMATION_MS: Long = 15_000

/**
 * Phase state of one forward session, shared by the session handle ([PortForwardSession]) and the
 * per-connection retry loops.
 *
 * The phase is carried by the session, not by the policy: the same [ForwardRetryPolicy] must fail
 * fast while the wizard waits for the Guest (connect-time) and keep retrying once the Guest is up.
 *
 * The dial outcomes are recorded for the recovery watcher. They are deliberately about *survival*,
 * not about bytes flowing: a stale-link dial is copied for seconds before the Guest drops it, so
 * neither "a connection is being copied" nor "the last dial lasted a while" proves an RD session.
 * Only a dial that outlived [confirmationMs] does.
 */
internal class SessionPhase(
    /**
     * How long a dial must survive to count as an established session. Overridable so tests can
     * exercise both sides of the threshold with short real dials.
     */
    private val confirmationMs: Long = DIAL_CONFIRMATION_MS,
    /** Clock used to classify an ended dial; overridable so a test can drive the threshold. */
    private val nowNanos: () -> Long = System::nanoTime
) {
    private val established = AtomicBoolean(false)
    private val failedDialCount = AtomicLong(0)

    /**
     * Accepted client sockets that have not ended yet, so a dial is visible from the moment the
     * Guest's TCP connection arrives - before its forward handshake has completed.
     */
    private val clientDials = AtomicInteger(0)

    /**
     * Handshakes of the connections currently being copied, with a count per timestamp.
     *
     * Keyed by [System.nanoTime] of the handshake rather than by connection, because the phase only
     * ever sees that timestamp. A count keeps two connections that handshook in the same nanosecond
     * distinct: removing one must not hide the other, or the watcher could lose the oldest pending
     * handshake and prod a session that is still establishing.
     */
    private val activeHandshakes = ConcurrentHashMap<Long, Int>()

    @Volatile
    private var lastHandshakeNanos: Long? = null

    @Volatile
    private var lastFailedNanos: Long? = null

    @Volatile
    private var lastLongLivedNanos: Long? = null

    /** Whether the Guest came up for this session; false until [markEstablished]. */
    val isEstablished: Boolean
        get() = established.get()

    /**
     * Whether the Guest has a dial open right now: an accepted client socket, handshake or not.
     *
     * The pre-handshake phase matters to the recovery watcher: a dial connecting through the
     * forwarder is not "the Guest gave up", even though it has no handshake to report yet.
     */
    val isClientDialed: Boolean
        get() = clientDials.get() > 0

    /**
     * Whether at least one connection whose handshake succeeded is still being copied.
     *
     * Not recovery evidence: see the class KDoc. The watcher corroborates with
     * [oldestActiveHandshakeNanos].
     */
    val hasActiveForward: Boolean
        get() = activeHandshakes.isNotEmpty()

    /** [System.nanoTime] of the last successful handshake, or null when none succeeded yet. */
    val lastSuccessfulHandshakeNanos: Long?
        get() = lastHandshakeNanos

    /**
     * The earliest handshake among the connections still being copied, or null when none is.
     *
     * This is how a connection that has not ended yet can still confirm: a handshake this old has
     * survived longer than [confirmationMs], so it is an established session rather than a dial the
     * Guest is about to drop.
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
     * Monotone count of failed dials since the forward started. The watcher compares it with the
     * count it saw at its last prod: the timestamp alone cannot tell several dials apart when more
     * than one ends between two samples.
     */
    val failedDials: Long
        get() = failedDialCount.get()

    /** Idempotent: the phase only ever moves from connect-time to steady-state. */
    fun markEstablished() {
        established.set(true)
    }

    /** Records *when* the handshake succeeded: the caller keeps the same value per connection. */
    fun recordSuccessfulHandshake(atNanos: Long) {
        lastHandshakeNanos = atNanos
    }

    /** A client socket was accepted: a dial exists from now on, handshake pending. */
    fun recordClientDial() {
        clientDials.incrementAndGet()
    }

    /** The dial the accepted socket belonged to is over: handshook or not. */
    fun recordClientDialEnded() {
        // clamped: a connection coroutine cancelled before it started has no finally, while the
        // listener close below resets the count, so a late end must not make it negative
        clientDials.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    /** The session's listener is closed: no dial can be in flight any more. */
    fun clearClientDials() {
        clientDials.set(0)
    }

    /** A connection's handshake succeeded, so it is being copied from [handshakeNanos] on. */
    fun recordActiveForward(handshakeNanos: Long) {
        activeHandshakes.merge(handshakeNanos, 1, Int::plus)
    }

    /**
     * A connection ended. [handshakeNanos] is the handshake of *this* connection: `null` when it
     * never handshook, in which case it never counted as an active forward and is not classified.
     *
     * The classification is about survival: a dial that ended within [confirmationMs] of its
     * handshake failed (the Guest dropped the stale-link dial), a later one was a real session the
     * server ended.
     */
    fun recordEndedForward(handshakeNanos: Long?) {
        if (handshakeNanos == null) {
            return
        }
        activeHandshakes.computeIfPresent(handshakeNanos) { _, count ->
            (count - 1).takeIf { it > 0 }
        }
        val endNanos = nowNanos()
        if (endNanos - handshakeNanos < confirmationMs * NANOS_PER_MILLI) {
            failedDialCount.incrementAndGet()
            lastFailedNanos = endNanos
        } else {
            lastLongLivedNanos = endNanos
        }
    }
}

/**
 * The Guest's dial outcomes on one forward session, as the recovery watcher observes them.
 *
 * Narrower than [PortForwardSession], and deliberately so: it exposes only facts that can confirm a
 * session or prove a link stale. "A connection is being copied" is not one of them - a stale-link
 * dial is copied for seconds before the Guest drops it - so the watcher cannot mistake it for
 * recovery.
 */
internal interface ForwardDials {
    /**
     * Whether the Guest has a dial open right now: an accepted client socket, handshake or not.
     *
     * A dial is not evidence of a session; it is evidence that the Guest has not given up, and that
     * prodding now would cut a reconnection the Guest's own loop is attempting.
     */
    val isClientDialed: Boolean

    /**
     * The earliest handshake among the connections still being copied, or null when none is. A
     * handshake older than [DIAL_CONFIRMATION_MS] confirms an established RD session.
     */
    val oldestActiveHandshakeNanos: Long?

    /** [System.nanoTime] when the last long-lived dial ended, or null when none lasted long. */
    val lastLongLivedDialNanos: Long?

    /** Monotone count of failed dials; the watcher compares it across a prod. */
    val failedDials: Long
}
