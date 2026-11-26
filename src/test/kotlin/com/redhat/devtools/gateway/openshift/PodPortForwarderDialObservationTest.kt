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

import io.kubernetes.client.PortForward
import io.mockk.every
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds

/**
 * The dial state [PodPortForwarder] reports to recovery: a dial in flight, the handshake
 * that confirms a session, and a dial that ended before or after confirmation.
 */
class PodPortForwarderDialObservationTest : PodPortForwarderTestBase() {

    @Test
    fun `#forward reports an active forward while a connection is copied`() {
        // given - a pod stream that never ends: only closing the session ends the connection
        stubForward(silentForwardResult())
        val session = PodPortForwarder(client).forward(pod, remotePort)

        // when - the Guest dialed and its connection is being copied
        assertThat(session.hasActiveForward).isFalse()
        assertThat(session.isClientDialed).isFalse()
        assertThat(session.oldestActiveHandshakeNanos).isNull()
        val socket = Socket("127.0.0.1", session.localPort)
        try {
            // then - the open connection is visible by its handshake, which is what can confirm it
            assertThat(awaitTrue(EVENT_TIMEOUT_MS) { session.hasActiveForward }).isTrue()
            assertThat(session.isClientDialed).isTrue()
            assertThat(session.oldestActiveHandshakeNanos).isNotNull()
        } finally {
            session.close()
            socket.close()
        }

        // then - the connection is over: neither active nor pending confirmation
        assertThat(awaitTrue(EVENT_TIMEOUT_MS) { !session.hasActiveForward }).isTrue()
        assertThat(session.isClientDialed).isFalse()
        assertThat(session.oldestActiveHandshakeNanos).isNull()
    }

    @Test
    fun `#forward reports a dialing client before its handshake`() {
        // given - the forward handshake never completes (Layer A keeps retrying the pod forward),
        // so the accepted socket is the only phase the session can report
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } throws IOException("connection reset")
        val session = PodPortForwarder(client) { PodLiveness.RUNNING }.forward(pod, remotePort)

        val socket = Socket("127.0.0.1", session.localPort)
        try {
            // when / then - the dial is visible before any handshake: recovery must not read a
            // connecting Guest as one that gave up
            assertThat(awaitTrue(EVENT_TIMEOUT_MS) { session.isClientDialed }).isTrue()
            assertThat(session.oldestActiveHandshakeNanos).isNull()
            assertThat(session.hasActiveForward).isFalse()
        } finally {
            socket.close()
            session.close()
        }

        // and it disappears with the dial
        assertThat(awaitTrue(EVENT_TIMEOUT_MS) { !session.isClientDialed }).isTrue()
    }

    @Test
    fun `#forward classifies a dial the client dropped before confirmation as failed`() {
        // given - the pod keeps the copy open and the Guest closes its dial within the confirmation
        // threshold, like the 6.5-6.9s stale-link dials measured against the Guest's 6s transport timeout
        stubForward(silentForwardResult())
        val session = PodPortForwarder(client, confirmationMs = TEST_CONFIRMATION_MS)
            .forward(pod, remotePort)

        val socket = Socket("127.0.0.1", session.localPort)
        try {
            // when - the handshake lands, then the client gives up on the dial
            assertThat(awaitTrue(EVENT_TIMEOUT_MS) { session.oldestActiveHandshakeNanos != null }).isTrue()
            Thread.sleep(TEST_CONFIRMATION_MS / 4)
            socket.close()

            assertThat(
                awaitTrue(EVENT_TIMEOUT_MS) {
                    session.lastFailedDialNanos != null
                }
            ).isTrue()

            // then
            assertThat(session.failedDials).isEqualTo(1)
            assertThat(session.lastLongLivedDialNanos).isNull()
        } finally {
            socket.close()
            session.close()
        }
    }

    @Test
    fun `#forward classifies a dial the pod ended before confirmation as failed`() {
        // given - the pod ends the copied connection well within the confirmation threshold, like a
        // restarted IDE server rejecting the stale session token of the cached link (CRW-13910:
        // this is the only stale-link evidence a same-pod server restart produces)
        val podStream = silentPodStream()
        stubForward(forwardResult(podStream))
        val session = PodPortForwarder(client, confirmationMs = TEST_CONFIRMATION_MS)
            .forward(pod, remotePort)

        try {
            // when - the handshake lands, then the server ends the dial
            Socket("127.0.0.1", session.localPort).use {
                assertThat(awaitTrue(EVENT_TIMEOUT_MS) { session.oldestActiveHandshakeNanos != null }).isTrue()
                podStream.close()

                assertThat(awaitTrue(EVENT_TIMEOUT_MS) { session.oldestActiveHandshakeNanos == null }).isTrue()
            }

            // then - the pod dropping a prompt dial is stale-link evidence, like the Guest dropping it
            assertThat(session.failedDials).isEqualTo(1)
            assertThat(session.lastFailedDialNanos).isNotNull()
        } finally {
            session.close()
        }
    }

    @Test
    fun `#forward classifies a dial that outlived confirmation as long-lived`() {
        // given - the pod holds the connection past the confirmation threshold, like a real session
        stubForward(forwardResult(eofAfter(TEST_CONFIRMATION_MS + LONG_LIVED_MARGIN_MS)))
        val session = PodPortForwarder(client, confirmationMs = TEST_CONFIRMATION_MS)
            .forward(pod, remotePort)

        try {
            // when
            Socket("127.0.0.1", session.localPort).use {
                assertThat(
                    awaitTrue(LONG_LIVED_DIAL_TIMEOUT_MS) {
                        session.lastLongLivedDialNanos != null
                    }
                ).isTrue()
            }

            // then - a long-lived dial is not evidence of a stale link
            assertThat(session.failedDials).isZero()
            assertThat(session.lastFailedDialNanos).isNull()
        } finally {
            session.close()
        }
    }

    @Test
    fun `#forward does not count a connection that never handshook`() {
        // given - the first connection fails its handshake while the pod is gone (terminal attempt),
        // the second one handshakes and stays connected
        val portForwardResult = silentForwardResult()
        var podAlive = false
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } answers {
            if (!podAlive) throw IOException("connection reset")
            portForwardResult
        }
        val forwarder = PodPortForwarder(client) {
            if (podAlive) PodLiveness.RUNNING else PodLiveness.GONE
        }
        val session = forwarder.forward(pod, remotePort)

        try {
            runBlocking { delay(100.milliseconds) }
            Socket("127.0.0.1", session.localPort).use { socket ->
                socket.soTimeout = EVENT_TIMEOUT_MS.toInt()
                assertThat(socket.inputStream.read()).isEqualTo(-1)
            }

            // when - the Guest re-dials while the pod serves again
            podAlive = true
            val socket = Socket("127.0.0.1", session.localPort)
            try {
                // then - the count was never decremented by the connection that never handshook
                assertThat(awaitTrue(EVENT_TIMEOUT_MS) { session.hasActiveForward }).isTrue()
            } finally {
                socket.close()
            }
        } finally {
            session.close()
        }
    }
}
