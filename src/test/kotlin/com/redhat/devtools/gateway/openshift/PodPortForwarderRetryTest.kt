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
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Retry behaviour of [PodPortForwarder]: how a failed handshake is retried in each phase, and when
 * the attempt cap, a gone client or a gone pod ends the retries. Retargeting a gone pod and the
 * outage budget live in [PodPortForwarderRetargetTest].
 */
class PodPortForwarderRetryTest : PodPortForwarderTestBase() {

    @Test
    fun `#forward keeps the client payload written while the handshake retries`() {
        // given - the first open fails, the client writes during the retry delay, the next succeeds
        val payload = "ping-payload"
        val serverOut = ByteArrayOutputStream()
        val secondResult = forwardResult(silentPodStream())
        every { secondResult.getOutboundStream(remotePort) } returns serverOut
        val attempts = AtomicInteger()
        val firstAttempt = CountDownLatch(1)
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } answers {
            if (attempts.incrementAndGet() == 1) {
                firstAttempt.countDown()
                throw IOException("connection reset")
            }
            secondResult
        }
        val session = PodPortForwarder(client) { PodLiveness.RUNNING }.forward(pod, remotePort)

        try {
            runBlocking { delay(100.milliseconds) }
            Socket("127.0.0.1", session.localPort).use { socket ->
                // the client pays its payload once the first handshake has failed
                assertThat(firstAttempt.await(EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue()
                socket.outputStream.write(payload.toByteArray())
                socket.outputStream.flush()

                // the probe that detects a live client read one byte and pushed it back: the copy
                // must still carry the whole payload, first byte included
                assertThat(awaitTrue(EVENT_TIMEOUT_MS) { serverOut.toString() == payload }).isTrue()
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `#forward tries several times if connecting fails`() {
        // given
        val attempts = AtomicInteger()
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } answers {
            attempts.incrementAndGet()
            throw IOException("connection reset")
        }
        val podsRunning = PodPortForwarder(client) { PodLiveness.RUNNING }

        // when
        val closeable = podsRunning.forward(pod, remotePort)

        // then
        // wait for the server to start
        runBlocking { delay(100.milliseconds) }
        // the client stays connected and the pod alive: the loop's own caps
        // (CONNECT_ATTEMPTS / retry budget) are what stop the retries
        val socket = Socket("127.0.0.1", closeable.localPort)
        try {
            socket.soTimeout = EVENT_TIMEOUT_MS.toInt()
            // attempts land at ~0s, ~1.2s and ~2.4s (1s reconnect delay + 2x100ms client-gone
            // probes between attempts); attempt #3 is the last one CONNECT_ATTEMPTS allows, and
            // by then the 2s budget is exhausted too. Awaiting the series instead of sleeping a
            // fixed time keeps this independent of scheduler load: a 4th attempt would leave the
            // socket open and the read below would time out.
            assertThat(
                awaitTrue(EVENT_TIMEOUT_MS) {
                    attempts.get() >= ForwardRetryPolicy.CONNECT_ATTEMPTS
                }
            ).isTrue()
            assertThat(socket.inputStream.read()).isEqualTo(-1)
            assertThat(attempts.get()).isEqualTo(ForwardRetryPolicy.CONNECT_ATTEMPTS)
        } finally {
            socket.close()
            closeable.close()
        }
    }

    @Test
    fun `#forward keeps retrying an established session past the connect-time attempt cap`() {
        // given - the handshake fails more often than connect-time allows, then succeeds
        val portForwardResult = silentForwardResult()
        val handshakes = AtomicInteger()
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } answers {
            if (handshakes.incrementAndGet() <= ESTABLISHED_HANDSHAKE_FAILURES) {
                throw IOException("connection reset")
            }
            portForwardResult
        }
        val podsRunning = PodPortForwarder(client) { PodLiveness.RUNNING }
        val session = podsRunning.forward(pod, remotePort)
        session.markEstablished()

        try {
            // when - the Guest re-dials once and stays connected across the outage
            runBlocking { delay(100.milliseconds) }
            Socket("127.0.0.1", session.localPort).use {
                // then - the loop outlives the 3-attempt connect-time cap and the handshake lands
                assertThat(
                    awaitTrue(STEADY_STATE_RETRY_TIMEOUT_MS) {
                        session.lastSuccessfulHandshakeNanos != null
                    }
                ).isTrue()
                assertThat(handshakes.get()).isEqualTo(ESTABLISHED_HANDSHAKE_FAILURES + 1)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `#markEstablished is idempotent and does not record a handshake`() {
        // given - a silent pod stream, so the handshake is the only observable event
        stubForward(silentForwardResult())
        val session = PodPortForwarder(client).forward(pod, remotePort)

        try {
            // when - connect calls it once, a re-run may call it again
            assertThat(session.isEstablished).isFalse()
            session.markEstablished()
            session.markEstablished()

            // then - only the phase moves forward: the accepted connection records the handshake
            assertThat(session.isEstablished).isTrue()
            assertThat(session.lastSuccessfulHandshakeNanos).isNull()
            Socket("127.0.0.1", session.localPort).use {
                assertThat(
                    awaitTrue(EVENT_TIMEOUT_MS) { session.lastSuccessfulHandshakeNanos != null }
                ).isTrue()
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `#forward does not retry when the client disconnects`() {
        // given - the handshake always fails while the pod stays alive,
        // so the disconnected client is the only reason to stop retrying
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } throws IOException("connection reset")
        val podsRunning = PodPortForwarder(client) { PodLiveness.RUNNING }

        // when
        val closeable = podsRunning.forward(pod, remotePort)
        try {
            // wait for the server to start
            runBlocking { delay(100.milliseconds) }
            Socket("127.0.0.1", closeable.localPort).apply {
                close() // the client drops immediately after connecting
            }

            // then - long enough for another retry to have happened if the close were not detected
            runBlocking { delay(2500.milliseconds) }
            verify(exactly = 1) { // the first attempt only, no retries for a gone client
                anyConstructed<PortForward>().forward(pod, listOf(remotePort))
            }
        } finally {
            closeable.close()
        }
    }

    @Test
    fun `#forward does not retry when the pod is gone`() {
        // given
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } throws podGoneException()
        val podsGone = PodPortForwarder(client) { PodLiveness.GONE }

        // when
        val closeable = podsGone.forward(pod, remotePort)
        runBlocking { delay(100.milliseconds) }

        // then - the accepted socket is closed by the first, failing attempt:
        // the client sees EOF instead of a connection left hanging
        Socket("127.0.0.1", closeable.localPort).use { socket ->
            socket.soTimeout = EVENT_TIMEOUT_MS.toInt()
            assertThat(socket.inputStream.read()).isEqualTo(-1)
        }
        verify(exactly = 1) {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        }
        closeable.close()
    }
}
