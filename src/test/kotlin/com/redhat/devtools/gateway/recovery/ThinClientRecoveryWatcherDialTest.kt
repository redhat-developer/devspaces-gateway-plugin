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
 * The Guest's dials around a forced reconnect: a dial in flight, one that has not confirmed yet, and the
 * doomed dials of a stale link that must not hold the forced reconnect back.
 */
class ThinClientRecoveryWatcherDialTest {

    private val harness = Harness()

    @Test
    fun `waits for an open connection to confirm instead of forcing a reconnect`() {
        // the Guest dialed and the connection is being copied, but it has not outlived a stale dial
        harness.openDial()

        harness.runWatcher()

        assertThat(harness.reconnects).isEmpty()
        assertThat(harness.recovered).isEqualTo(1)
        assertThat(harness.failed).isZero()
    }

    @Test
    fun `does not treat an open connection as recovery before it confirms`() {
        // the connection ends before the confirmation threshold, like a stale-link dial
        harness.openDial(atNanos = 0)
        harness.scheduleFailedDialEnd(handshakeNanos = 0, afterMs = DIAL_CONFIRMATION_MS / 2)
        harness.stopAfterReconnects = 1

        harness.runWatcher()

        assertThat(harness.recovered).isZero()
        assertThat(harness.inPlaceReconnects).isEqualTo(1) // it was a doomed dial: reconnect in place
        assertThat(harness.reconnects).isEmpty() // in place, no project restart
    }

    /**
     * The CRW-13910 live failure: the Guest's doomed dial died while its next seamless attempt was
     * already connecting. Forcing a reconnect there cancelled the dial that was about to work and restarted
     * the project, which the user saw as the window being replaced.
     */

    @Test
    fun `does not force a reconnect while a newer dial is in flight when the doomed dial dies`() {
        harness.openDial(atNanos = 0) // dial H, opened while the network was down
        harness.scheduleDialHandshake(afterMs = 4_000) // the next seamless attempt, about to work
        harness.scheduleFailedDialEnd(handshakeNanos = 0, afterMs = 6_000) // dial H's timeout

        harness.runWatcher()

        assertThat(harness.reconnects).isEmpty() // the attempt in flight was not cut
        assertThat(harness.recovered).isEqualTo(1)
        assertThat(harness.failed).isZero()
    }

    /**
     * The CRW-13910 in-place idea-server restart, from the live capture: the Guest's seamless loop
     * re-dialed every ~5 s while each doomed dial lived ~6.5 s (its own 6 s transport timeout), so a
     * dial was in flight at every sample. Waiting for one to end never terminates; a run of failed
     * dials must outrank it.
     */

    @Test
    fun `reconnects while a doomed dial is in flight when the Guest redials before the previous one dies`() {
        harness.refreshedLink = REFRESHED_LINK
        harness.stopAfterReconnects = 1
        harness.scheduleDoomedDial(handshakeAtMs = 0, lifetimeMs = 6_500)
        harness.scheduleDoomedDial(handshakeAtMs = 5_000, lifetimeMs = 6_500)
        harness.scheduleDoomedDial(handshakeAtMs = 10_000, lifetimeMs = 6_500) // still open at the forced reconnect

        harness.runWatcher()

        // stale rules applied from the first forced reconnect: overlapping failed dials proved the link stale
        assertThat(harness.reconnects).hasSize(1)
        assertThat(harness.reconnects[0].rawFragment).contains("p=2")
        assertThat(harness.refreshes).isEqualTo(1)
        // dial 3 opened at 10 s and only dies at 16.5 s: the forced reconnect fired with it still open, as soon
        // as dials 1 and 2 had both failed. Waiting for the dial to end would postpone it to 16.5 s
        // at best and forever while the Guest keeps overlapping dials.
        assertThat(harness.firstReconnectAtMs).isGreaterThanOrEqualTo(11_000)
        assertThat(harness.firstReconnectAtMs).isLessThan(16_500)
    }

    @Test
    fun `waits for a dial that is connecting before its handshake`() {
        // an accepted socket with no handshake yet: the Guest is dialing, not gone
        harness.acceptConnectingDial()
        harness.scheduleDialHandshake(afterMs = REDIAL_WAIT_MS + 1_000)

        harness.runWatcher()

        assertThat(harness.reconnects).isEmpty()
        assertThat(harness.recovered).isEqualTo(1)
    }
}
