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
import com.redhat.devtools.gateway.openshift.DIAL_CONFIRMATION_MS
import com.redhat.devtools.gateway.openshift.ForwardDials
import com.redhat.devtools.gateway.testutil.podWith
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI

/** Poll interval of the virtual clock; production uses 5s and 15s. */
internal const val POLL_MS = 1_000L

internal const val LONG_POLL_MS = 3_000L
internal const val LONG_POLL_AFTER_MS = 4_000L
internal const val REDIAL_WAIT_MS = 2_000L

/** Larger than [DIAL_CONFIRMATION_MS], so an episode can wait a connection out and still reach it. */
internal const val BUDGET_MS = 60_000L

internal const val RUN_TIMEOUT_MS = 20_000L

/** When the Guest answers a forced reconnect: one dial outcome, well inside the redial window. */
internal const val DIAL_AFTER_MS = 300L

internal const val NONCE_PARAMETER = ThinClientJoinLink.RECONNECT_PARAMETER

internal val CACHED_LINK: URI = URI("tcp://127.0.0.1:41000#cb=123&p=1")
internal val REFRESHED_LINK: URI = URI("tcp://127.0.0.1:41000#cb=123&p=2")

internal val OLD_POD = podWith("luke-skywalker-roll-old", "764b6480-old-uid")
internal val NEW_POD = podWith("luke-skywalker-roll-new", "7a011c35-new-uid")

/**
 * A virtual clock: the loop only reads time through it, and only advances it by sleeping.
 * [sleep] suspends for a real millisecond so `stop()` still cancels the loop.
 *
 * [schedule] models the world changing as time passes (the Guest answers a forced reconnect a moment later):
 * the action runs once the clock reaches its time. Scheduled actions must not advance the clock
 * themselves, so firing never re-enters.
 */
internal class TestClock {
    private var nanos: Long = 0
    private val scheduled = mutableListOf<ScheduledAction>()

    private class ScheduledAction(val atNanos: Long, val action: () -> Unit)

    fun now(): Long = nanos

    fun schedule(atNanos: Long, action: () -> Unit) {
        scheduled += ScheduledAction(atNanos, action)
    }

    fun advance(millis: Long) {
        nanos += millis * NANOS_PER_MILLISECOND
        fireDue()
    }

    private fun fireDue() {
        while (true) {
            val due = scheduled.filter { it.atNanos <= nanos }.minByOrNull { it.atNanos } ?: return
            scheduled.remove(due)
            due.action()
        }
    }

    @Suppress("ConvertLongToDuration")
    suspend fun sleep(millis: Long) {
        advance(millis)
        delay(1)
    }
}

internal const val NANOS_PER_MILLISECOND = 1_000_000L

/**
 * Records the Guest's dials as the watcher observes them.
 *
 * A dial is visible from the accepted socket on ([isDialConnecting]) and keeps existing once it
 * handshook (an entry in [openHandshakes]) until it ends. Several dials can overlap, as the live
 * capture showed: a doomed dial and the Guest's next seamless attempt.
 */
internal class FakeDials : ForwardDials {
    private val openHandshakes = mutableListOf<Long>()

    /** A dial whose socket is accepted but whose forward handshake has not completed yet. */
    var isDialConnecting: Boolean = false

    override val isClientDialed: Boolean
        get() = isDialConnecting || openHandshakes.isNotEmpty()

    override val oldestActiveHandshakeNanos: Long?
        get() = openHandshakes.minOrNull()

    override var lastLongLivedDialNanos: Long? = null
    override var failedDials: Long = 0

    /** The dial handshook at [atNanos]: it is now being copied. */
    fun handshake(atNanos: Long) {
        isDialConnecting = false
        openHandshakes += atNanos
    }

    /** The dial whose handshake was [handshakeNanos] ended before it could confirm. */
    fun endDial(handshakeNanos: Long) {
        openHandshakes.remove(handshakeNanos)
        failedDials++
    }
}

/**
 * Everything a watcher test drives: the fakes, the virtual clock, and the hooks that let a test
 * script what the Guest's dials did.
 */
internal class Harness {

    val clock = TestClock()
    val dials = FakeDials()

    /** Every forced reconnect attempt, in order; a throwing one is an attempt too. */
    val reconnects = mutableListOf<URI>()

    /** How many times the watcher reconnected the Guest in place, re-dialing its current link. */
    var inPlaceReconnects: Int = 0

    /** Every reconnect request, in place or forced: the escalation is measured across both. */
    val actionCount: Int
        get() = inPlaceReconnects + reconnects.size

    /** Virtual time (ms) of every reconnect request, so a test can assert the silence windows. */
    val reconnectTimesMs = mutableListOf<Long>()
    val probedPods = mutableListOf<V1Pod>()
    var resolvedPods = 0
    var refreshes = 0
    var recovered = 0
    var failed = 0

    /** Virtual time of the first forced reconnect, so a test can assert when the observation let it through. */
    var firstReconnectAtMs: Long = -1

    /** The pod the cached link was built against; `null` disables the roll check. */
    var sessionPod: V1Pod? = null

    /** The pod that serves the workspace now. */
    var pod: V1Pod? = podWith("luke-skywalker", "skywalker-uid")
    var probeFailure: Exception? = null
    var refreshFailure: Exception? = null
    var reconnectFailure: Exception? = null
    var refreshedLink: URI? = null
    var clientPresent = true

    /** The link a refresh reports, by call number: lets a test model a server that is not ready yet. */
    var refreshLinkResult: (call: Int) -> URI? = { refreshedLink }

    /** Runs after every forced reconnect attempt, so a test can script how the Guest answers it. */
    var onReconnect: () -> Unit = {}

    /** Stops the loop after this many reconnects: a test that does not want further iterations. */
    var stopAfterReconnects: Int? = null

    lateinit var watcher: ThinClientRecoveryWatcher

    private val timing = ThinClientRecoveryWatcher.Timing(
        pollIntervalMs = POLL_MS,
        longPollIntervalMs = LONG_POLL_MS,
        longPollAfterMs = LONG_POLL_AFTER_MS,
        redialWaitMs = REDIAL_WAIT_MS,
        budgetMs = BUDGET_MS,
        nowNanos = clock::now,
        sleep = clock::sleep
    )

    /** The Guest dialed and its forward handshake landed at [atNanos]: a connection is copied. */
    fun openDial(atNanos: Long = clock.now()) {
        dials.handshake(atNanos)
    }

    /** A dial's socket is accepted but its forward handshake has not completed yet. */
    fun acceptConnectingDial() {
        dials.isDialConnecting = true
    }

    /** A connecting dial's handshake lands [afterMs] later. */
    fun scheduleDialHandshake(afterMs: Long) {
        after(afterMs) { dials.handshake(clock.now()) }
    }

    /** The dial that handshook at [handshakeNanos] ends [afterMs] later, before it can confirm. */
    fun scheduleFailedDialEnd(handshakeNanos: Long, afterMs: Long) {
        after(afterMs) { dials.endDial(handshakeNanos) }
    }

    /**
     * A doomed dial as the live capture saw it: its handshake lands [handshakeAtMs] after this call
     * and the Guest drops it [lifetimeMs] later, before it can confirm. Several of them can overlap,
     * which is what the Guest's seamless loop does while a stale link is cached.
     */
    fun scheduleDoomedDial(handshakeAtMs: Long, lifetimeMs: Long) {
        val handshakeNanos = clock.now() + handshakeAtMs * NANOS_PER_MILLISECOND
        clock.schedule(handshakeNanos) { dials.handshake(handshakeNanos) }
        clock.schedule(handshakeNanos + lifetimeMs * NANOS_PER_MILLISECOND) {
            dials.endDial(handshakeNanos)
        }
    }

    /** The Guest's answer to a forced reconnect: [count] failed dials, [afterMs] later. */
    fun scheduleFailedDials(afterMs: Long, count: Int = 2) {
        after(afterMs) { dials.failedDials += count }
    }

    /** The Guest window is gone [afterMs] later: the watcher must stop without reporting anything. */
    fun scheduleClientGone(afterMs: Long) {
        after(afterMs) { clientPresent = false }
    }

    /**
     * The Guest keeps dialing without ever handshaking or failing a dial: the snapshot keeps
     * changing, so the observation's idle window alone would never let it end.
     */
    fun churnDialsForever(intervalMs: Long) {
        fun scheduleNext() {
            after(intervalMs) {
                dials.isDialConnecting = !dials.isDialConnecting
                scheduleNext()
            }
        }
        scheduleNext()
    }

    fun advanceClock(millis: Long) {
        clock.advance(millis)
    }

    fun stopWatcher() {
        watcher.stop()
    }

    val isWatcherActive: Boolean
        get() = watcher.isActive()

    private fun after(afterMs: Long, dial: () -> Unit) {
        clock.schedule(clock.now() + afterMs * NANOS_PER_MILLISECOND, dial)
    }

    fun newWatcher(): ThinClientRecoveryWatcher {
        watcher = ThinClientRecoveryWatcher(environment(), timing)
        return watcher
    }

    private fun environment(): ThinClientRecoveryWatcher.RecoveryEnvironment =
        ThinClientRecoveryWatcher.RecoveryEnvironment(
            resolvePod = {
                resolvedPods++
                pod
            },
            probe = { probedPod ->
                probedPods += probedPod
                probeFailure.throwIfSet()
                true
            },
            cachedLink = CACHED_LINK,
            sessionPod = sessionPod,
            refreshLink = {
                refreshes++
                refreshFailure.throwIfSet()
                refreshLinkResult(refreshes)
            },
            reconnectInPlace = {
                inPlaceReconnects++
                recordReconnectAttempt()
            },
            forceReconnect = { link ->
                reconnects += link
                recordReconnectAttempt()
            },
            session = dials,
            isClientPresent = { clientPresent },
            onRecovered = { recovered++ },
            onRecoveryFailed = { failed++ }
        )

    /** Records one delivered reconnect request, in place or forced, and runs the test's answer hook. */
    private fun recordReconnectAttempt() {
        reconnectTimesMs += clock.now() / NANOS_PER_MILLISECOND
        if (firstReconnectAtMs < 0) {
            firstReconnectAtMs = clock.now() / NANOS_PER_MILLISECOND
        }
        reconnectFailure.throwIfSet()
        onReconnect()
        if (actionCount == stopAfterReconnects) {
            watcher.stop()
        }
    }
}

/** Throws a scripted failure, if the test set one: keeps the fakes free of three throw sites. */
internal fun Exception?.throwIfSet() {
    if (this != null) throw this
}

    /** Runs the loop to completion; every case ends by recovery, the budget, or a stop. */
    internal fun Harness.runWatcher(startTimes: Int = 1) = runBlocking {
        withTimeout(RUN_TIMEOUT_MS) {
            val watcher = newWatcher()
            repeat(startTimes) { watcher.start(this) }
            watcher.awaitEpisodeEnd()
        }
    }

    /** Waits for the episode [ThinClientRecoveryWatcher.start] began: it returns only whether it did. */
    internal suspend fun ThinClientRecoveryWatcher.awaitEpisodeEnd() {
        while (isActive()) delay(1)
    }
