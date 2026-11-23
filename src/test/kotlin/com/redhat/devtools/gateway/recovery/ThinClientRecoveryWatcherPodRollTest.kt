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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import kotlin.concurrent.thread

/**
 * Pod rolls and stale links: a cached link built against a replaced pod is stale, a stale link is
 * proven by failed dials, and only a fresh link may be sent.
 */
class ThinClientRecoveryWatcherPodRollTest {

    private val harness = Harness()

    @Test
    fun `a replaced pod is stale even while a doomed connection is open`() {
        harness.sessionPod = OLD_POD
        harness.pod = NEW_POD
        harness.refreshedLink = REFRESHED_LINK
        harness.openDial()
        harness.stopAfterReconnects = 1

        harness.runWatcher()

        // an open, unconfirmed dial is not recovery, and the stale link can only be fixed by a
        // fresh one: the very first forced reconnect carries it
        assertThat(harness.recovered).isZero()
        assertThat(harness.refreshes).isEqualTo(1)
        assertThat(harness.reconnects).hasSize(1)
        assertThat(harness.reconnects[0].rawFragment).contains("p=2")
    }

    @Test
    fun `reports recovery after a replaced pod only when a dial confirms`() {
        harness.sessionPod = OLD_POD
        harness.pod = NEW_POD
        harness.refreshedLink = REFRESHED_LINK
        harness.onReconnect = { harness.openDial() }

        harness.runWatcher()

        assertThat(harness.reconnects).hasSize(1)
        assertThat(harness.reconnects[0].rawFragment).contains("p=2")
        assertThat(harness.recovered).isEqualTo(1)
    }

    @Test
    fun `never reconnects the cached link after a pod roll while no fresh link exists`() {
        // the live failure (CRW-13910, idea-server restart in place): the link is known stale and
        // the replacement server does not report a link yet, so forcing a reconnect could only burn the
        // Guest's project-open retries - the Guest closed itself after two such reconnects
        harness.sessionPod = OLD_POD
        harness.pod = NEW_POD
        harness.refreshedLink = null

        harness.runWatcher()

        assertThat(harness.reconnects).isEmpty()
        assertThat(harness.refreshes).isGreaterThan(1) // kept asking for a fresh link
        assertThat(harness.failed).isEqualTo(1) // the budget, exactly once
    }

    @Test
    fun `never reconnects a stale link proven by failed dials while no fresh link exists`() {
        harness.refreshedLink = null
        harness.onReconnect = {}
        harness.scheduleFailedDials(afterMs = DIAL_AFTER_MS, count = 2)

        harness.runWatcher()

        assertThat(harness.reconnects).isEmpty()
        assertThat(harness.refreshes).isGreaterThan(1)
        assertThat(harness.failed).isEqualTo(1)
    }

    @Test
    fun `reconnects the fresh link as soon as the IDE server reports one`() {
        harness.sessionPod = OLD_POD
        harness.pod = NEW_POD
        harness.refreshedLink = REFRESHED_LINK
        // the new server needs a few polls before it can report a link
        harness.refreshLinkResult = { call -> if (call <= 3) null else REFRESHED_LINK }
        harness.stopAfterReconnects = 2

        harness.runWatcher()

        // the cached link was never sent; the first delivered forced reconnect already carries the fresh one
        assertThat(harness.reconnects).hasSize(2)
        assertThat(harness.reconnects[0].rawFragment).contains("p=2")
        assertThat(harness.reconnects[0].rawFragment).doesNotContain(NONCE_PARAMETER)
        assertThat(harness.reconnects[1].rawFragment).contains("$NONCE_PARAMETER=2")
        assertThat(harness.refreshes).isGreaterThan(3) // the null stretch plus one rebuild per forced reconnect
    }

    @Test
    fun `does not treat the same pod after a roll check as a roll`() {
        harness.sessionPod = OLD_POD
        harness.pod = OLD_POD
        harness.refreshedLink = REFRESHED_LINK
        harness.stopAfterReconnects = 1

        harness.runWatcher()

        assertThat(harness.refreshes).isZero()
        assertThat(harness.inPlaceReconnects).isEqualTo(1) // the unchanged link is used in place
        assertThat(harness.reconnects).isEmpty()
    }

    @Test
    fun `reports recovery without a forced reconnect when the already established session is confirmed`() {
        harness.openDial(atNanos = 0)

        harness.runWatcher()

        assertThat(harness.recovered).isEqualTo(1)
        assertThat(harness.reconnects).isEmpty()
    }

    @Test
    fun `refreshes the link when the pod rolls after a cached-link reconnect in place`() {
        harness.sessionPod = OLD_POD
        harness.pod = OLD_POD
        harness.refreshedLink = REFRESHED_LINK
        harness.stopAfterReconnects = 2
        harness.onReconnect = {
            if (harness.actionCount == 1) {
                harness.pod = NEW_POD
            }
        }

        harness.runWatcher()

        assertThat(harness.actionCount).isEqualTo(2)
        assertThat(harness.inPlaceReconnects).isEqualTo(1) // first request kept the still-valid cached link
        assertThat(harness.reconnects).hasSize(1)
        assertThat(harness.reconnects[0].rawFragment).contains("p=2")
        assertThat(harness.refreshes).isEqualTo(1)
    }

    /**
     * A delivered refreshed link that later failed dials prove stale again is rebuilt for the next
     * forced reconnect. The delivered refresh keeps the observation phase enabled, so the watcher still waits
     * out the Guest's silence instead of forcing a reconnect at once.
     */

    @Test
    fun `refreshes again when failed dials prove the delivered link stale`() {
        harness.sessionPod = OLD_POD
        harness.pod = NEW_POD
        harness.refreshedLink = REFRESHED_LINK
        harness.stopAfterReconnects = 2
        harness.onReconnect = {
            if (harness.reconnects.size == 1) {
                harness.scheduleFailedDials(DIAL_AFTER_MS, count = 2)
            }
        }

        harness.runWatcher()

        assertThat(harness.reconnects).hasSize(2)
        assertThat(harness.reconnects).doesNotContain(CACHED_LINK)
        assertThat(harness.reconnects[0].rawFragment).contains("p=2")
        assertThat(harness.reconnects[0].rawFragment).doesNotContain(NONCE_PARAMETER)
        assertThat(harness.reconnects[1].rawFragment).contains("p=2")
        assertThat(harness.reconnects[1].rawFragment).contains("$NONCE_PARAMETER=2")
        assertThat(harness.refreshes).isEqualTo(2)
        // the episode kept observing after the delivered refresh: the next forced reconnect follows a full
        // silence window, not the immediate send a re-disabled observation phase would produce
        assertThat(harness.reconnectTimesMs[1] - harness.reconnectTimesMs[0])
            .isBetween(REDIAL_WAIT_MS * 2, REDIAL_WAIT_MS * 2 + POLL_MS)
    }

    /**
     * A confirmed pod-roll recovery replaces the session's link and pod. The next outage must start
     * from them: reconnecting the Guest's current link in place is enough when that link did not
     * change, and comparing against the old pod would report another roll for a plain network blip.
     */
    @Test
    fun `adopts the refreshed link and serving pod for the next episode`(): Unit = runBlocking {
        harness.sessionPod = OLD_POD
        harness.pod = NEW_POD
        harness.refreshedLink = REFRESHED_LINK
        harness.stopAfterReconnects = 2
        var dialHandshakeNanos = 0L
        harness.onReconnect = {
            if (harness.actionCount == 1) {
                dialHandshakeNanos = harness.clock.now()
                harness.openDial(dialHandshakeNanos)
            }
        }
        val watcher = harness.newWatcher()

        withTimeout(RUN_TIMEOUT_MS) {
            assertThat(watcher.start(this)).isTrue()
            watcher.awaitEpisodeEnd()
        }
        assertThat(harness.recovered).isEqualTo(1)
        assertThat(harness.reconnects).hasSize(1)
        assertThat(harness.reconnects[0].rawFragment).contains("p=2")
        val refreshesAfterFirstEpisode = harness.refreshes
        // the confirmed session is over: the next outage must not be mistaken for this one
        harness.dials.endDial(dialHandshakeNanos)

        withTimeout(RUN_TIMEOUT_MS) {
            assertThat(watcher.start(this)).isTrue()
            watcher.awaitEpisodeEnd()
        }

        // the adopted link is the one the Guest already stored: the next outage reconnects it in place
        assertThat(harness.actionCount).isEqualTo(2)
        assertThat(harness.inPlaceReconnects).isEqualTo(1)
        assertThat(harness.reconnects).hasSize(1)
        // the serving pod was adopted too: no second roll, no second refresh
        assertThat(harness.refreshes).isEqualTo(refreshesAfterFirstEpisode)
    }
}
