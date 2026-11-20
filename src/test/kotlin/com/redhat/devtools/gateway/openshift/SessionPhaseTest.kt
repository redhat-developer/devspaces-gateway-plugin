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
        phase.recordActiveForward(HANDSHAKE_NANOS)
        phase.recordActiveForward(HANDSHAKE_NANOS)

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

    @Test
    fun `clamps a client dial end below zero`() {
        phase.recordClientDialEnded()

        assertThat(phase.isClientDialed).isFalse()
    }

    @Test
    fun `a dial ending after clearClientDials stays at zero`() {
        phase.recordClientDial()
        phase.clearClientDials()

        phase.recordClientDialEnded()

        assertThat(phase.isClientDialed).isFalse()
    }
}
