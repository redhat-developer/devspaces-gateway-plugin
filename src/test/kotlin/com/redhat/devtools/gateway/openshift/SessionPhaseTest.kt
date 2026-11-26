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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.Socket

class SessionPhaseTest {

    private companion object {
        private const val CONFIRMATION_MS = 1_000L
        private const val HANDSHAKE_NANOS = 5_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }

    private var nowNanos: Long = 0
    private val phase = SessionPhase(CONFIRMATION_MS) { nowNanos }

    @Test
    fun `keeps a shared handshake active until the last connection ends`() {
        phase.recordActiveForward(HANDSHAKE_NANOS, acceptedAtNanos = 0)
        phase.recordActiveForward(HANDSHAKE_NANOS, acceptedAtNanos = 0)

        nowNanos = HANDSHAKE_NANOS + CONFIRMATION_MS / 2 * NANOS_PER_MILLI
        phase.recordEndedForward(HANDSHAKE_NANOS)

        // two connections handshook in the same nanosecond: one ending must not hide the other
        assertThat(phase.oldestActiveHandshakeNanos).isEqualTo(HANDSHAKE_NANOS)
        assertThat(phase.failedDials).isEqualTo(1) // ended before the confirmation threshold

        nowNanos = HANDSHAKE_NANOS + CONFIRMATION_MS * 2 * NANOS_PER_MILLI
        phase.recordEndedForward(HANDSHAKE_NANOS)

        assertThat(phase.oldestActiveHandshakeNanos).isNull()
        assertThat(phase.lastLongLivedDialNanos).isEqualTo(nowNanos) // outlived confirmation
        assertThat(phase.failedDials).isEqualTo(1)
    }

    /**
     * The live 2026-11-25 failure: a five-minute network cut queued sockets through the outage. Each
     * handshook the moment connectivity returned and the Guest dropped it right after. Those drops
     * say nothing about the link, so they must not become stale-link evidence and force a project
     * restart.
     */
    @Test
    fun `does not count a dial whose handshake was stalled by an outage as a failed dial`() {
        val acceptedAtNanos = HANDSHAKE_NANOS - CONFIRMATION_MS * NANOS_PER_MILLI
        phase.recordActiveForward(HANDSHAKE_NANOS, acceptedAtNanos)

        nowNanos = HANDSHAKE_NANOS + CONFIRMATION_MS / 2 * NANOS_PER_MILLI
        phase.recordEndedForward(HANDSHAKE_NANOS)

        assertThat(phase.failedDials).isZero()
        assertThat(phase.lastFailedDialNanos).isNull()
    }

    /**
     * A dial dropped inside the window is stale-link evidence regardless of which side ended it: the
     * Guest's transport timeout ends it client side, a restarted IDE server rejecting the stale
     * session token ends it pod side (live 2026-11-26 regression: excluding the pod side left the
     * same-pod server restart without stale-link evidence).
     */
    @Test
    fun `counts a prompt handshake dropped before it confirms`() {
        // a normal dial: accepted and handshook right away, then dropped inside the window
        phase.recordActiveForward(HANDSHAKE_NANOS, acceptedAtNanos = HANDSHAKE_NANOS)

        nowNanos = HANDSHAKE_NANOS + CONFIRMATION_MS / 2 * NANOS_PER_MILLI
        phase.recordEndedForward(HANDSHAKE_NANOS)

        assertThat(phase.failedDials).isEqualTo(1)
        assertThat(phase.lastFailedDialNanos).isEqualTo(nowNanos)
    }

    @Test
    fun `a stale dial end does not clear a newer dial`() {
        val staleDial = Socket()
        phase.recordClientDial(staleDial)
        phase.clearClientDials()

        val currentDial = Socket()
        phase.recordClientDial(currentDial)

        // the old handler ending after the reset must not remove the newer dial
        phase.recordClientDialEnded(staleDial)
        assertThat(phase.isClientDialed).isTrue()

        phase.recordClientDialEnded(currentDial)
        assertThat(phase.isClientDialed).isFalse()
    }
}
