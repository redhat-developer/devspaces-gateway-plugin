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

import com.redhat.devtools.gateway.openshift.DIAL_CONFIRMATION_MS
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

/** Larger than [DIAL_CONFIRMATION_MS], so an episode can wait a connection out and still reach it. */
private const val BUDGET_MS = 60_000L

private const val RUN_TIMEOUT_MS = 20_000L

/** When the Guest answers a prod: one dial outcome, well inside the redial window. */
private const val DIAL_AFTER_MS = 300L

private const val NONCE_PARAMETER = ThinClientJoinLink.RECONNECT_PARAMETER

private val CACHED_LINK: URI = URI("tcp://127.0.0.1:41000#cb=123&p=1")
private val REFRESHED_LINK: URI = URI("tcp://127.0.0.1:41000#cb=123&p=2")

private fun pod(name: String, uid: String): V1Pod = V1Pod().apply {
    metadata = V1ObjectMeta().apply {
        this.name = name
        this.uid = uid
    }
}

private val OLD_POD = pod("luke-skywalker-roll-old", "764b6480-old-uid")
private val NEW_POD = pod("luke-skywalker-roll-new", "7a011c35-new-uid")

/**
 * Behaviour of the recovery loop.
 *
 * Every dependency is a fake and the clock is virtual: each sleep advances it and then suspends for
 * a millisecond, so the loop terminates deterministically, `stop()` still cancels it promptly, and
 * the suite never waits out a real interval or touches a cluster.
 *
 * The dial outcomes are scripted as *scheduled* events: the Guest answers a prod (or drops a doomed
 * dial) a moment later, which is what the watcher's observation windows must see.
 */
class ThinClientRecoveryWatcherTest {

    private val harness = Harness()

    // --- polling ------------------------------------------------------------

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
    fun `polls less often once the outage lasts`() {
        harness.pod = null

        harness.runWatcher()

        // one poll per second would be 60 list calls; the long-outage interval keeps it far below
        assertThat(harness.resolvedPods).isGreaterThan(10)
        assertThat(harness.resolvedPods).isLessThan(30)
    }

    // --- prods --------------------------------------------------------------

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

    // --- confirmation-based recovery evidence -------------------------------

    @Test
    fun `waits for an open connection to confirm instead of prodding it`() {
        // the Guest dialed and the connection is being copied, but it has not outlived a stale dial
        harness.openDial()

        harness.runWatcher()

        assertThat(harness.prods).isEmpty()
        assertThat(harness.recovered).isEqualTo(1)
        assertThat(harness.failed).isZero()
    }

    @Test
    fun `does not treat an open connection as recovery before it confirms`() {
        // the connection ends before the confirmation threshold, like a stale-link dial
        harness.openDial()
        harness.scheduleFailedDialEnd(DIAL_CONFIRMATION_MS / 2)
        harness.stopAfterProds = 1

        harness.runWatcher()

        assertThat(harness.recovered).isZero()
        assertThat(harness.prods).hasSize(1) // it was a doomed dial: prod
    }

    @Test
    fun `reports recovery when a dial ends after it outlived the confirmation threshold`() {
        harness.onProd = {
            harness.openDial()
            harness.scheduleLongLivedDialEnd(DIAL_CONFIRMATION_MS + 500)
        }

        harness.runWatcher()

        assertThat(harness.recovered).isEqualTo(1)
        assertThat(harness.prods).hasSize(1)
        assertThat(harness.failed).isZero()
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

    // --- stale link by dial evidence ----------------------------------------

    @Test
    fun `refreshes the link after two failed dials since the prod`() {
        harness.refreshedLink = REFRESHED_LINK
        harness.stopAfterProds = 2
        harness.onProd = { harness.scheduleFailedDials(DIAL_AFTER_MS, count = 2) }

        harness.runWatcher()

        assertThat(harness.prods).hasSize(2)
        assertThat(harness.refreshes).isEqualTo(1)
        assertThat(harness.prods[1].rawFragment).contains("p=2")
        assertThat(harness.prods[1].rawFragment).contains("$NONCE_PARAMETER=2")
    }

    @Test
    fun `does not refresh the link for a single failed dial`() {
        harness.stopAfterProds = 2
        harness.onProd = { harness.scheduleFailedDials(DIAL_AFTER_MS, count = 1) }

        harness.runWatcher()

        assertThat(harness.refreshes).isZero()
        assertThat(harness.prods[1].rawFragment).contains("p=1")
        assertThat(harness.prods[1].rawFragment).contains("$NONCE_PARAMETER=2")
    }

    @Test
    fun `survives client-closed failed dials and refreshes the stale link`() {
        // the live failure (CRW-13910): dials the Guest closes itself fire no onRemoteEnd, so the
        // episode must not depend on those signals to keep running and reach the stale-link path
        harness.refreshedLink = REFRESHED_LINK
        harness.openDial() // a doomed dial is open right now
        harness.scheduleFailedDialEnd(DIAL_CONFIRMATION_MS / 2)
        harness.onProd = { harness.scheduleFailedDials(DIAL_AFTER_MS, count = 2) }
        harness.stopAfterProds = 2

        harness.runWatcher()

        assertThat(harness.recovered).isZero()
        assertThat(harness.prods).hasSize(2)
        assertThat(harness.refreshes).isEqualTo(1)
        assertThat(harness.prods[1].rawFragment).contains("p=2")
    }

    // --- stale link by pod identity ----------------------------------------

    @Test
    fun `a replaced pod is stale even while a doomed connection is open`() {
        harness.sessionPod = OLD_POD
        harness.pod = NEW_POD
        harness.refreshedLink = REFRESHED_LINK
        harness.openDial()
        harness.stopAfterProds = 1

        harness.runWatcher()

        // an open, unconfirmed dial is not recovery, and the stale link can only be fixed by a
        // fresh one: the very first prod carries it
        assertThat(harness.recovered).isZero()
        assertThat(harness.refreshes).isEqualTo(1)
        assertThat(harness.prods).hasSize(1)
        assertThat(harness.prods[0].rawFragment).contains("p=2")
    }

    @Test
    fun `reports recovery after a replaced pod only when a dial confirms`() {
        harness.sessionPod = OLD_POD
        harness.pod = NEW_POD
        harness.refreshedLink = REFRESHED_LINK
        harness.onProd = { harness.openDial() }

        harness.runWatcher()

        assertThat(harness.prods).hasSize(1)
        assertThat(harness.prods[0].rawFragment).contains("p=2")
        assertThat(harness.recovered).isEqualTo(1)
    }

    @Test
    fun `keeps polling when a replaced pod cannot report a link yet`() {
        harness.sessionPod = OLD_POD
        harness.pod = NEW_POD
        harness.refreshedLink = null
        harness.stopAfterProds = 2

        harness.runWatcher()

        // falling back per the current rules: the cached link, with the nonce from the second prod on
        assertThat(harness.prods).hasSize(2)
        assertThat(harness.refreshes).isEqualTo(2)
        assertThat(harness.prods[0]).isEqualTo(CACHED_LINK)
        assertThat(harness.prods[1].rawFragment).contains("$NONCE_PARAMETER=2")
    }

    @Test
    fun `does not treat the same pod after a roll check as a roll`() {
        harness.sessionPod = OLD_POD
        harness.pod = OLD_POD
        harness.refreshedLink = REFRESHED_LINK
        harness.stopAfterProds = 1

        harness.runWatcher()

        assertThat(harness.refreshes).isZero()
        assertThat(harness.prods).containsExactly(CACHED_LINK)
    }

    // --- episode lifecycle --------------------------------------------------

    @Test
    fun `reports recovery without a prod when the already established session is confirmed`() {
        harness.openDial(atNanos = 0)

        harness.runWatcher()

        assertThat(harness.recovered).isEqualTo(1)
        assertThat(harness.prods).isEmpty()
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
    fun `repeated episodes report one recovery for the same established session`() {
        harness.openDial(atNanos = 0)
        harness.advanceClock(DIAL_CONFIRMATION_MS + 1)

        harness.runWatcher(startTimes = 3)

        // every onRemoteEnd starts an episode; only the first observes a new session
        assertThat(harness.recovered).isEqualTo(1)
        assertThat(harness.prods).isEmpty()
    }

    @Test
    fun `stop ends the loop before the next prod`() {
        harness.onProd = { harness.stopWatcher() }

        harness.runWatcher()

        assertThat(harness.prods).hasSize(1)
        assertThat(harness.isWatcherActive).isFalse()
        assertThat(harness.failed).isZero()
    }

    // --- failures inside the loop -------------------------------------------

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
        harness.onProd = { harness.scheduleFailedDials(DIAL_AFTER_MS, count = 2) }

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

/** Records the Guest's dials as the watcher observes them. */
private class FakeDials : ForwardDials {
    override var oldestActiveHandshakeNanos: Long? = null
    override var lastLongLivedDialNanos: Long? = null
    override var failedDials: Long = 0
}

/**
 * Everything a watcher test drives: the fakes, the virtual clock, and the hooks that let a test
 * script what the Guest's dials did.
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

    /** The pod the cached link was built against; `null` disables the roll check. */
    var sessionPod: V1Pod? = null

    /** The pod that serves the workspace now. */
    var pod: V1Pod? = pod("luke-skywalker", "skywalker-uid")
    var probeSucceeds = true
    var probeFailure: Exception? = null
    var refreshFailure: Exception? = null
    var prodFailure: Exception? = null
    var refreshedLink: URI? = null
    var clientPresent = true

    /** Virtual time a link refresh takes: a stale link's status exec can outlast the redial window. */
    var refreshDelayMs = 0L

    /** Runs after every prod attempt, so a test can script how the Guest answers it. */
    var onProd: () -> Unit = {}

    /** Stops the loop after this many prods: a test that does not want further iterations. */
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

    /** The Guest opens a connection that is being copied from [atNanos] on. */
    fun openDial(atNanos: Long = clock.now()) {
        dials.oldestActiveHandshakeNanos = atNanos
    }

    /** The open connection ends [afterMs] later, before it can confirm: a failed dial. */
    fun scheduleFailedDialEnd(afterMs: Long) {
        after(afterMs) {
            dials.oldestActiveHandshakeNanos = null
            dials.failedDials++
        }
    }

    /** The open connection outlives the threshold and then ends: a long-lived dial. */
    fun scheduleLongLivedDialEnd(afterMs: Long) {
        after(afterMs) {
            dials.oldestActiveHandshakeNanos = null
            dials.lastLongLivedDialNanos = clock.now()
        }
    }

    /** The Guest's answer to a prod: [count] failed dials, [afterMs] later. */
    fun scheduleFailedDials(afterMs: Long, count: Int = 2) {
        after(afterMs) {
            dials.failedDials += count
        }
    }

    /** A long-lived dial ends [afterMs] after the prod. */
    fun scheduleLongLivedDial(afterMs: Long) {
        after(afterMs) { dials.lastLongLivedDialNanos = clock.now() }
    }

    fun advanceClock(millis: Long) {
        clock.advance(millis)
    }

    fun stopWatcher() {
        watcher.stop()
    }

    val isWatcherActive: Boolean
        get() = watcher.isActive

    private fun after(afterMs: Long, dial: () -> Unit) {
        clock.schedule(clock.now() + afterMs * NANOS_PER_MILLISECOND, dial)
    }

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
                sessionPod = sessionPod,
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

/** Throws a scripted failure, if the test set one: keeps the fakes free of three throw sites. */
private fun Exception?.throwIfSet() {
    if (this != null) throw this
}
