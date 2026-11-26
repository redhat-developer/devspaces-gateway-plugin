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

import com.redhat.devtools.gateway.openshift.DIAL_CONFIRMATION_MS
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import kotlin.concurrent.thread

/**
 * Lifecycle of a recovery episode: start/stop, one loop and one report per episode, the budget,
 * silence escalation, and a failing probe/refresh/forced reconnect that is retried instead of
 * ending the loop.
 */
class ThinClientRecoveryWatcherLifecycleTest {

    private val harness = Harness()

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
        harness.stopAfterReconnects = 2

        harness.runWatcher(startTimes = 3)

        // one iteration per reconnect request: a second concurrent loop would resolve and reconnect twice as often
        assertThat(harness.actionCount).isEqualTo(2)
        assertThat(harness.inPlaceReconnects).isEqualTo(2)
        assertThat(harness.resolvedPods).isEqualTo(2)
    }

    @Test
    fun `repeated episodes report one recovery for the same established session`() = runBlocking {
        harness.openDial(atNanos = 0)
        harness.advanceClock(DIAL_CONFIRMATION_MS + 1)
        val watcher = harness.newWatcher()

        withTimeout(RUN_TIMEOUT_MS) {
            // every onRemoteEnd of the settling dial churn starts an episode on the same watcher
            repeat(3) {
                watcher.start(this)
                watcher.awaitEpisodeEnd()
            }
        }

        assertThat(harness.recovered).isEqualTo(1)
        assertThat(harness.reconnects).isEmpty()
    }

    /**
     * The CRW-13910 live failure: 9 s after the budget-exhausted report the Guest's dial churn fired
     * `onRemoteEnd` again and started a second ten-minute episode - a failure report every ten
     * minutes forever instead of exactly one.
     */

    @Test
    fun `refuses to start again once the budget was exhausted`(): Unit = runBlocking {
        harness.pod = null
        val watcher = harness.newWatcher()

        withTimeout(RUN_TIMEOUT_MS) {
            assertThat(watcher.start(this)).isTrue()
            watcher.awaitEpisodeEnd()
            val reconnectsAtFailure = harness.reconnects.size
            val podsAtFailure = harness.resolvedPods

            assertThat(watcher.start(this)).isFalse()
            watcher.awaitEpisodeEnd()

            assertThat(harness.failed).isEqualTo(1)
            assertThat(harness.reconnects).hasSize(reconnectsAtFailure)
            assertThat(harness.resolvedPods).isEqualTo(podsAtFailure)
        }
    }

    @Test
    fun `starts a new episode after one ended by recovery`(): Unit = runBlocking {
        harness.openDial(atNanos = 0)
        val watcher = harness.newWatcher()

        withTimeout(RUN_TIMEOUT_MS) {
            assertThat(watcher.start(this)).isTrue()
            watcher.awaitEpisodeEnd()
            assertThat(harness.recovered).isEqualTo(1)

            // the session is usable again, so the next connection loss must be watched again
            assertThat(watcher.start(this)).isTrue()
            watcher.awaitEpisodeEnd()
        }

        assertThat(harness.recovered).isEqualTo(1) // the same session is still reported once
    }

    @Test
    fun `start returns false while an episode is already running`(): Unit = runBlocking {
        harness.pod = null
        val watcher = harness.newWatcher()

        withTimeout(RUN_TIMEOUT_MS) {
            assertThat(watcher.start(this)).isTrue()
            assertThat(watcher.isActive()).isTrue()

            assertThat(watcher.start(this)).isFalse()

            watcher.stop()
            watcher.awaitEpisodeEnd()
        }
    }

    /**
     * An owner scope that is already cancelled cannot run the loop, so starting must roll back the
     * active latch instead of leaving an orphan that refuses every later start.
     */

    @Test
    fun `start on a cancelled scope leaves the watcher free for a live one`(): Unit = runBlocking {
        harness.stopAfterReconnects = 1
        val watcher = harness.newWatcher()
        val cancelledScope = CoroutineScope(SupervisorJob()).also { it.cancel() }

        assertThat(watcher.start(cancelledScope)).isFalse()
        assertThat(watcher.isActive()).isFalse()

        withTimeout(RUN_TIMEOUT_MS) {
            assertThat(watcher.start(this)).isTrue()
            watcher.awaitEpisodeEnd()
        }

        assertThat(harness.actionCount).isEqualTo(1)
    }

    /**
     * A scope that turns inactive after [ThinClientRecoveryWatcher.start]'s check: the job completes
     * cancelled without ever reaching the loop's `finally`. Completion itself must release the latch
     * so a later episode can start.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancellation before the loop starts releases the watcher for a new episode`() = runTest {
        val watcher = harness.newWatcher()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

        assertThat(watcher.start(scope)).isTrue()
        scope.cancel()
        advanceUntilIdle()
        assertThat(watcher.isActive()).isFalse()

        harness.stopAfterReconnects = 1
        assertThat(watcher.start(this)).isTrue()
        watcher.awaitEpisodeEnd()

        assertThat(harness.actionCount).isEqualTo(1)
    }

    @Test
    fun `stop ends the loop before the next reconnect request`() {
        harness.onReconnect = { harness.stopWatcher() }

        harness.runWatcher()

        assertThat(harness.actionCount).isEqualTo(1)
        assertThat(harness.isWatcherActive).isFalse()
        assertThat(harness.failed).isZero()
    }

    @Test
    fun `stop from another thread ends the episode`(): Unit = runBlocking {
        harness.pod = null
        val watcher = harness.newWatcher()

        withTimeout(RUN_TIMEOUT_MS) {
            assertThat(watcher.start(this)).isTrue()
            thread { watcher.stop() }.join()
            watcher.awaitEpisodeEnd()
        }

        assertThat(watcher.isActive()).isFalse()
        assertThat(harness.failed).isZero()
    }

    @Test
    fun `starts a new episode after one ended by stop`(): Unit = runBlocking {
        harness.pod = null
        val watcher = harness.newWatcher()

        withTimeout(RUN_TIMEOUT_MS) {
            assertThat(watcher.start(this)).isTrue()
            watcher.stop()
            watcher.awaitEpisodeEnd()
            assertThat(harness.failed).isZero()

            // only a budget-ended episode latches the watcher: a stop leaves recovery pending
            assertThat(watcher.start(this)).isTrue()
            watcher.stop()
            watcher.awaitEpisodeEnd()
        }
    }

    /**
     * `stop()` does not wait for the cancelled loop: its `finally` runs later. A replacement started
     * in between must keep its latch — a superseded run releasing it would end the replacement
     * before it resolved a single pod.
     */
    @Test
    fun `a superseded run does not release the latch of its replacement`(): Unit = runBlocking {
        harness.pod = null
        val watcher = harness.newWatcher()

        withTimeout(RUN_TIMEOUT_MS) {
            assertThat(watcher.start(this)).isTrue()
            delay(5) // let the first run enter its poll sleep
            watcher.stop()
            assertThat(watcher.start(this)).isTrue()

            // the replacement gets to run its own episode to the budget; ending right away without
            // a failure report would prove its latch was cleared by the superseded run
            watcher.awaitEpisodeEnd()
        }

        assertThat(harness.failed).isEqualTo(1)
    }

    @Test
    fun `sends nothing when the budget expires while building the link`() {
        // given — a pod roll makes the link rebuild unavoidable, and that build outlasts the budget
        harness.sessionPod = OLD_POD
        harness.pod = NEW_POD
        harness.refreshLinkResult = {
            harness.advanceClock(BUDGET_MS)
            REFRESHED_LINK
        }

        harness.runWatcher()

        // then — no forced reconnect swaps the Guest's window right before the failure is reported
        assertThat(harness.reconnects).isEmpty()
        assertThat(harness.failed).isEqualTo(1)
    }

    /**
     * The Guest churns dials that never handshake: the snapshot keeps changing, so the idle window
     * never fires and only the episode budget can end the observation.
     */

    @Test
    fun `ends the episode at the budget when dials churn without ever confirming`(): Unit = runBlocking {
        harness.churnDialsForever(intervalMs = 500)
        val watcher = harness.newWatcher()

        withTimeout(RUN_TIMEOUT_MS) {
            assertThat(watcher.start(this)).isTrue()
            watcher.awaitEpisodeEnd()

            assertThat(harness.reconnects).isEmpty()
            assertThat(harness.failed).isEqualTo(1) // exactly one failure report
            assertThat(watcher.start(this)).isFalse() // the definitive-failure latch is engaged
        }
    }

    @Test
    fun `escalates the silence window after reconnect requests that yielded no dial`() {
        harness.stopAfterReconnects = 3

        harness.runWatcher()

        assertThat(harness.actionCount).isEqualTo(3)
        assertThat(harness.inPlaceReconnects).isEqualTo(3)
        val firstGapMs = harness.reconnectTimesMs[1] - harness.reconnectTimesMs[0]
        val secondGapMs = harness.reconnectTimesMs[2] - harness.reconnectTimesMs[1]
        assertThat(secondGapMs).isGreaterThan(firstGapMs)
    }

    @Test
    fun `resets the silence escalation when dials are observed`() {
        harness.refreshedLink = REFRESHED_LINK
        harness.stopAfterReconnects = 2
        harness.onReconnect = { harness.scheduleFailedDials(DIAL_AFTER_MS, count = 2) }

        harness.runWatcher()

        assertThat(harness.actionCount).isEqualTo(2)
        // the failed dials of request 1 reset the escalation: the next silence window stays at the base
        // redial wait instead of doubling
        assertThat(harness.reconnectTimesMs[1] - harness.reconnectTimesMs[0])
            .isBetween(REDIAL_WAIT_MS * 2, REDIAL_WAIT_MS * 2 + POLL_MS)
    }

    @Test
    fun `a lone failed dial does not escalate the silence window`() {
        harness.refreshedLink = REFRESHED_LINK
        harness.stopAfterReconnects = 2
        harness.onReconnect = {
            if (harness.actionCount == 1) {
                harness.scheduleFailedDials(DIAL_AFTER_MS, count = 1)
            }
        }

        harness.runWatcher()

        assertThat(harness.actionCount).isEqualTo(2)
        // the Guest answered with one failed dial: the next silence window stays at the base
        // redial wait instead of doubling as it does after a reconnect request that yielded nothing
        assertThat(harness.reconnectTimesMs[1] - harness.reconnectTimesMs[0])
            .isBetween(REDIAL_WAIT_MS * 2, REDIAL_WAIT_MS * 2 + POLL_MS)
    }

    @Test
    fun `stops quietly when the client disappears while observing a dial`() {
        harness.acceptConnectingDial()
        harness.scheduleClientGone(afterMs = 500)

        harness.runWatcher()

        assertThat(harness.reconnects).isEmpty()
        assertThat(harness.failed).isZero()
        assertThat(harness.recovered).isZero()
    }

    @Test
    fun `retries a throwing probe instead of failing`() {
        harness.probeFailure = IOException("API server unreachable")

        harness.runWatcher()

        assertThat(harness.reconnects).isEmpty()
        assertThat(harness.probedPods.size).isGreaterThan(1)
        assertThat(harness.failed).isEqualTo(1)
    }

    @Test
    fun `retries a throwing link refresh instead of failing`() {
        harness.refreshFailure = IOException("status exec failed")
        harness.onReconnect = { harness.scheduleFailedDials(DIAL_AFTER_MS, count = 2) }

        harness.runWatcher()

        // the first prod needs no link; attempt 2 could not build the rebuilt one it needs
        assertThat(harness.actionCount).isEqualTo(1)
        assertThat(harness.inPlaceReconnects).isEqualTo(1)
        assertThat(harness.refreshes).isGreaterThan(1)
        assertThat(harness.failed).isEqualTo(1)
    }

    @Test
    fun `retries a throwing reconnect instead of failing`() {
        harness.reconnectFailure = IllegalStateException("the client handle is dead")

        harness.runWatcher()

        assertThat(harness.actionCount).isGreaterThan(1)
        assertThat(harness.failed).isEqualTo(1)
    }
}
