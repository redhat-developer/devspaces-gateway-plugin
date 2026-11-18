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

import com.redhat.devtools.gateway.openshift.ForwardDials
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI

/** Poll interval of the virtual clock; production uses 5s and 15s. */
private const val POLL_MS = 1_000L

private const val LONG_POLL_MS = 3_000L
private const val LONG_POLL_AFTER_MS = 4_000L
private const val REDIAL_WAIT_MS = 2_000L
private const val BUDGET_MS = 10_000L
private const val RUN_TIMEOUT_MS = 10_000L

/** When the Guest answers a prod: one dial outcome, well inside the redial window. */
private const val DIAL_AFTER_MS = 300L

private const val NONCE_PARAMETER = ThinClientJoinLink.RECONNECT_PARAMETER

private val CACHED_LINK: URI = URI("tcp://127.0.0.1:41000#cb=123&p=1")
private val REFRESHED_LINK: URI = URI("tcp://127.0.0.1:41000#cb=123&p=2")

/**
 * Behaviour of the recovery loop.
 *
 * Every dependency is a fake and the clock is virtual: each sleep advances it and then suspends for
 * a millisecond, so the loop terminates deterministically, `stop()` still cancels it promptly, and
 * the suite never waits out a real interval or touches a cluster.
 */
class ThinClientRecoveryWatcherTest {

    private val harness = Harness()

    @Test
    fun `does not prod while the probe fails and keeps polling`() {
        harness.probeSucceeds = false

        harness.runWatcher()

        assertThat(harness.prods).isEmpty()
        assertThat(harness.probedPods.size).isGreaterThan(1)
        assertThat(harness.failed).isEqualTo(1) // the budget, not a crash, ended the loop
    }

    @Test
    fun `does not probe or prod without a running pod and fails once the budget is spent`() {
        harness.pod = null

        harness.runWatcher()

        assertThat(harness.resolvedPods).isGreaterThan(1)
        assertThat(harness.probedPods).isEmpty()
        assertThat(harness.prods).isEmpty()
        assertThat(harness.failed).isEqualTo(1)
    }

    @Test
    fun `prods once with the cached link`() {
        harness.stopAfterProds = 1

        harness.runWatcher()

        assertThat(harness.prods).containsExactly(CACHED_LINK)
        assertThat(harness.recovered).isZero()
        assertThat(harness.failed).isZero()
    }

    @Test
    fun `prods again with a different link when the Guest ignored the first prod`() {
        harness.stopAfterProds = 2

        harness.runWatcher()

        assertThat(harness.prods).hasSize(2)
        assertThat(harness.prods[0]).isEqualTo(CACHED_LINK)
        assertThat(harness.prods[1]).isNotEqualTo(harness.prods[0])
        // nothing stale: the cached link plus the reconnect nonce defeats the no-op guard, and the
        // costly status exec of a link refresh is not paid
        assertThat(harness.refreshes).isZero()
        assertThat(harness.prods[1].rawFragment).contains("$NONCE_PARAMETER=2")
        assertThat(harness.prods[1].rawFragment).contains("p=1")
    }

    @Test
    fun `falls back to the cached link when no fresh link can be built`() {
        harness.stopAfterProds = 2
        harness.refreshedLink = null // the IDE server is not ready yet
        harness.onProd = { harness.scheduleFastFailedDials(DIAL_AFTER_MS, count = 2) }

        harness.runWatcher()

        assertThat(harness.refreshes).isEqualTo(1)
        assertThat(harness.prods[1]).isNotEqualTo(harness.prods[0])
        assertThat(harness.prods[1].rawFragment).contains("p=1")
        assertThat(harness.prods[1].rawFragment).contains("$NONCE_PARAMETER=2")
    }

    @Test
    fun `reports recovery when a long-lived dial followed the prod`() {
        harness.onProd = { harness.scheduleLongLivedDial(DIAL_AFTER_MS) }

        harness.runWatcher()

        assertThat(harness.recovered).isEqualTo(1)
        assertThat(harness.failed).isZero()
        assertThat(harness.prods).hasSize(1)
        assertThat(harness.isWatcherActive).isFalse()
    }

    @Test
    fun `reports recovery when a handshake just succeeded`() {
        harness.onProd = { harness.scheduleHandshake(DIAL_AFTER_MS) }

        harness.runWatcher()

        assertThat(harness.recovered).isEqualTo(1)
        assertThat(harness.prods).hasSize(1)
    }

    @Test
    fun `treats only fast-failed dials as a stale link and refreshes it`() {
        harness.refreshedLink = REFRESHED_LINK
        harness.stopAfterProds = 2
        harness.onProd = { harness.scheduleFastFailedDials(DIAL_AFTER_MS, count = 2) }

        harness.runWatcher()

        assertThat(harness.prods).hasSize(2)
        assertThat(harness.refreshes).isEqualTo(1)
        assertThat(harness.prods[1].rawFragment).contains("p=2")
        assertThat(harness.prods[1].rawFragment).contains("$NONCE_PARAMETER=2")
    }

    @Test
    fun `does not refresh the link for a single fast-failed dial`() {
        harness.stopAfterProds = 2
        harness.onProd = { harness.scheduleFastFailedDials(DIAL_AFTER_MS, count = 1) }

        harness.runWatcher()

        assertThat(harness.refreshes).isZero()
        assertThat(harness.prods[1].rawFragment).contains("p=1")
        assertThat(harness.prods[1].rawFragment).contains("$NONCE_PARAMETER=2")
    }

    @Test
    fun `measures the redial window from the delivered prod`() {
        harness.refreshedLink = REFRESHED_LINK
        // a stale link's status exec takes longer than the whole redial window
        harness.refreshDelayMs = REDIAL_WAIT_MS * 2
        harness.onProd = {
            if (harness.prods.size == 1) {
                harness.scheduleFastFailedDials(DIAL_AFTER_MS, count = 2)
            } else {
                harness.scheduleLongLivedDial(DIAL_AFTER_MS)
            }
        }

        harness.runWatcher()

        // the window opened when prod 2 was sent, so the Guest's re-dial after it is still observed
        assertThat(harness.prods).hasSize(2)
        assertThat(harness.recovered).isEqualTo(1)
        assertThat(harness.failed).isZero()
    }

    @Test
    fun `reports recovery without a prod when the Guest already holds a connection`() {
        harness.holdActiveForward()

        harness.runWatcher()

        assertThat(harness.recovered).isEqualTo(1)
        assertThat(harness.prods).isEmpty()
        assertThat(harness.resolvedPods).isZero()
    }

    @Test
    fun `stops quietly when the JetBrains Client is gone`() {
        harness.clientPresent = false

        harness.runWatcher()

        assertThat(harness.recovered).isZero()
        assertThat(harness.failed).isZero()
        assertThat(harness.resolvedPods).isZero()
    }

    @Test
    fun `polls less often once the outage lasts`() {
        harness.pod = null

        harness.runWatcher()

        // 1s polls for the first 4s, then 3s polls: 6 list calls, not 10
        assertThat(harness.resolvedPods).isEqualTo(6)
    }

    @Test
    fun `reports failure exactly once when the budget is spent`() {
        harness.pod = null

        harness.runWatcher()

        assertThat(harness.failed).isEqualTo(1)
    }

    @Test
    fun `repeated starts spawn a single loop`() {
        harness.stopAfterProds = 2

        harness.runWatcher(startTimes = 3)

        // one iteration per prod: a second concurrent loop would resolve and prod twice as often
        assertThat(harness.prods).hasSize(2)
        assertThat(harness.resolvedPods).isEqualTo(2)
    }

    @Test
    fun `stop ends the loop before the next prod`() {
        harness.onProd = harness::stopWatcher

        harness.runWatcher()

        assertThat(harness.prods).hasSize(1)
        assertThat(harness.isWatcherActive).isFalse()
        assertThat(harness.failed).isZero()
    }

    @Test
    fun `retries a throwing probe instead of failing`() {
        harness.probeFailure = IOException("API server unreachable")

        harness.runWatcher()

        assertThat(harness.prods).isEmpty()
        assertThat(harness.probedPods.size).isGreaterThan(1)
        assertThat(harness.failed).isEqualTo(1)
    }

    @Test
    fun `retries a throwing link refresh instead of failing`() {
        harness.refreshFailure = IOException("status exec failed")
        harness.onProd = { harness.scheduleFastFailedDials(DIAL_AFTER_MS, count = 2) }

        harness.runWatcher()

        assertThat(harness.prods).hasSize(1) // attempt 2 could not build a link
        assertThat(harness.refreshes).isGreaterThan(1)
        assertThat(harness.failed).isEqualTo(1)
    }

    @Test
    fun `retries a throwing prod instead of failing`() {
        harness.prodFailure = IllegalStateException("the client handle is dead")

        harness.runWatcher()

        assertThat(harness.prods.size).isGreaterThan(1)
        assertThat(harness.failed).isEqualTo(1)
    }

    /** Runs the loop to completion; every case ends by recovery, the budget, or a stop. */
    private fun Harness.runWatcher(startTimes: Int = 1) = runBlocking {
        withTimeout(RUN_TIMEOUT_MS) {
            val watcher = newWatcher()
            var job = watcher.start(this)
            repeat(startTimes - 1) { job = watcher.start(this) }
            job?.join()
        }
    }
}

/**
 * A virtual clock: the loop only reads time through it, and only advances it by sleeping.
 * [sleep] suspends for a real millisecond so `stop()` still cancels the loop.
 *
 * [schedule] models the world changing as time passes (the Guest answers a prod a moment later):
 * the action runs once the clock reaches its time. Scheduled actions must not advance the clock
 * themselves, so firing never re-enters.
 */
private class TestClock {
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

private const val NANOS_PER_MILLISECOND = 1_000_000L

/** Throws a scripted failure, if the test set one: keeps the fakes free of three throw sites. */
private fun Exception?.throwIfSet() {
    if (this != null) throw this
}

/** Records what the recovery loop did to the Guest's dials. */
private class FakeDials : ForwardDials {
    override var hasActiveForward: Boolean = false
    override var lastSuccessfulHandshakeNanos: Long? = null
    override var lastFastFailedDialNanos: Long? = null
    override var lastLongLivedDialNanos: Long? = null
    override var fastFailedDials: Long = 0
}

/**
 * Everything a watcher test drives: the fakes, the virtual clock, and the hooks that let a fake
 * prod change what the next dial observation looks like.
 */
private class Harness {

    val clock = TestClock()
    val dials = FakeDials()

    /** Every prod attempt, in order; a throwing prod is an attempt too. */
    val prods = mutableListOf<URI>()
    val probedPods = mutableListOf<V1Pod>()
    var resolvedPods = 0
    var refreshes = 0
    var recovered = 0
    var failed = 0

    var pod: V1Pod? = V1Pod().apply { metadata = V1ObjectMeta().apply { name = "luke-skywalker" } }
    var probeSucceeds = true
    var probeFailure: Exception? = null
    var refreshFailure: Exception? = null
    var prodFailure: Exception? = null
    var refreshedLink: URI? = null
    var clientPresent = true

    /** Virtual time a link refresh takes: a stale link's status exec can outlast the redial window. */
    var refreshDelayMs = 0L

    /** Runs after every prod attempt, so a test can script what the Guest's dial did. */
    var onProd: () -> Unit = {}

    /** Stops the loop after this many prods: a test that does not want a second iteration. */
    var stopAfterProds: Int? = null

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

    /** One fast-failed dial run, [afterMs] after the prod: it ends [count] times, never handshaking. */
    fun scheduleFastFailedDials(afterMs: Long, count: Int = 2) {
        after(afterMs) {
            dials.fastFailedDials += count
            dials.lastFastFailedDialNanos = clock.now()
        }
    }

    /** A handshake that succeeded [afterMs] after the prod and whose connection is still open. */
    fun scheduleHandshake(afterMs: Long) {
        after(afterMs) { dials.lastSuccessfulHandshakeNanos = clock.now() }
    }

    /** A connection that stayed up long enough to count as long-lived, ending [afterMs] after the prod. */
    fun scheduleLongLivedDial(afterMs: Long) {
        after(afterMs) { dials.lastLongLivedDialNanos = clock.now() }
    }

    private fun after(afterMs: Long, dial: () -> Unit) {
        clock.schedule(clock.now() + afterMs * NANOS_PER_MILLISECOND, dial)
    }

    /** The Guest is already bridged: an active forward exists before the loop starts. */
    fun holdActiveForward() {
        dials.hasActiveForward = true
    }

    fun stopWatcher() {
        watcher.stop()
    }

    val isWatcherActive: Boolean
        get() = watcher.isActive

    fun newWatcher(): ThinClientRecoveryWatcher {
        watcher = ThinClientRecoveryWatcher(
            ThinClientRecoveryWatcher.Collaborators(
                resolvePod = {
                    resolvedPods++
                    pod
                },
                probe = { probedPod ->
                    probedPods += probedPod
                    probeFailure.throwIfSet()
                    probeSucceeds
                },
                cachedLink = CACHED_LINK,
                refreshLink = {
                    refreshes++
                    if (refreshDelayMs > 0) clock.advance(refreshDelayMs)
                    refreshFailure.throwIfSet()
                    refreshedLink
                },
                prod = { link ->
                    prods += link
                    prodFailure.throwIfSet()
                    onProd()
                    if (prods.size == stopAfterProds) {
                        watcher.stop()
                    }
                },
                session = dials,
                isClientPresent = { clientPresent },
                onRecovered = { recovered++ },
                onRecoveryFailed = { failed++ }
            ),
            timing
        )
        return watcher
    }
}
