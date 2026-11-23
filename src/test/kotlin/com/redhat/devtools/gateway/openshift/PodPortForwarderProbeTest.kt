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
import io.kubernetes.client.openapi.models.V1Pod
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Probe and bounded-open behaviour of [PodPortForwarder]: a probe that opens and
 * closes at once, and an open that hangs past its bound instead of freezing the dial.
 */
class PodPortForwarderProbeTest : PodPortForwarderTestBase() {

    @Test
    fun `#probe succeeds when the forward opens and the streams close cleanly`() = runBlocking {
        // given
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        val podIn = mockk<InputStream>(relaxed = true)
        val podOut = mockk<OutputStream>(relaxed = true)
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } returns portForwardResult
        every { portForwardResult.getInputStream(remotePort) } returns podIn
        every { portForwardResult.getOutboundStream(remotePort) } returns podOut

        // when
        val opened = PodPortForwarder(client).probe(pod, remotePort)

        // then - the forward is opened and closed at once: no listener, no client socket
        assertThat(opened).isTrue()
        verify { podIn.close() }
        verify { podOut.close() }
    }

    @Test
    fun `#probe fails when the forward cannot be opened`() = runBlocking {
        // given
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } throws IOException("connection reset")

        // when / then
        assertThat(PodPortForwarder(client).probe(pod, remotePort)).isFalse()
    }

    @Test
    fun `#probe fails when the open hangs past the bound`() = runBlocking {
        // given - the pod websocket never initializes: client-java's open blocks until interrupted
        val releaseOpen = CountDownLatch(1)
        val openFinished = CountDownLatch(1)
        val hangingOpen: (V1Pod, Int) -> PortForward.PortForwardResult = { _, _ ->
            try {
                releaseOpen.await(HUNG_OPEN_CAP_MS, TimeUnit.MILLISECONDS)
                hangingOpenResult()
            } finally {
                openFinished.countDown()
            }
        }
        val forwarder = PodPortForwarder(
            client,
            probeTimeoutMs = PROBE_BOUND_MS,
            openForward = hangingOpen
        )

        // when
        val startedAtNanos = System.nanoTime()
        val opened = forwarder.probe(pod, remotePort)
        val elapsedMs = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI

        try {
            // then - a hanging open counts as a failed probe within the bound, and the blocked open
            // is interrupted, so it does not stay pending for the life of the process
            assertThat(opened).isFalse()
            assertThat(elapsedMs).isLessThan(HUNG_OPEN_CAP_MS)
            assertThat(openFinished.await(OPEN_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue()
        } finally {
            // if the interruption ever regressed, fail the assertion above instead of hanging here
            releaseOpen.countDown()
        }
    }

    @Test
    fun `#forward reclaims a dial whose open hangs`() {
        // given - the live freeze (CRW-13910): client-java's PortForward.forward blocks in
        // waitForInitialized() forever, so the dial could neither retry nor end
        val releaseOpen = CountDownLatch(1)
        val openAttempts = AtomicInteger()
        val hangingResult = hangingOpenResult()
        val hangingOpen: (V1Pod, Int) -> PortForward.PortForwardResult = { _, _ ->
            openAttempts.incrementAndGet()
            // every open blocks until its bound interrupts it, like the stalled websocket
            releaseOpen.await(HUNG_OPEN_CAP_MS, TimeUnit.MILLISECONDS)
            hangingResult
        }
        val forwarder = PodPortForwarder(
            client,
            openTimeoutMs = OPEN_BOUND_MS,
            openForward = hangingOpen
        ) { PodLiveness.RUNNING }
        val session = forwarder.forward(pod, remotePort)
        val socket = Socket("127.0.0.1", session.localPort)

        try {
            // when / then - the dial is visible, its bounded opens fail retryably, the loop goes on,
            // and the dial can still end: the state the recovery observation froze on clears again
            assertThat(awaitTrue(EVENT_TIMEOUT_MS) { session.isClientDialed }).isTrue()
            assertThat(awaitTrue(EVENT_TIMEOUT_MS) { openAttempts.get() >= 2 }).isTrue()
        } finally {
            releaseOpen.countDown()
            socket.close()
            session.close()
        }
        assertThat(awaitTrue(EVENT_TIMEOUT_MS) { !session.isClientDialed }).isTrue()
    }

    @Test
    fun `#forward retries a dial whose open timed out`() {
        // given - the first open hangs and is bounded; the pod then answers normally
        val portForwardResult = silentForwardResult()
        val releaseOpen = CountDownLatch(1)
        val openAttempts = AtomicInteger()
        val firstOpenHangs: (V1Pod, Int) -> PortForward.PortForwardResult = { _, _ ->
            if (openAttempts.incrementAndGet() == 1) {
                try {
                    releaseOpen.await(HUNG_OPEN_CAP_MS, TimeUnit.MILLISECONDS)
                } finally {
                    releaseOpen.countDown()
                }
            }
            portForwardResult
        }
        val forwarder = PodPortForwarder(
            client,
            openTimeoutMs = OPEN_BOUND_MS,
            openForward = firstOpenHangs
        ) { PodLiveness.RUNNING }
        val session = forwarder.forward(pod, remotePort)
        val socket = Socket("127.0.0.1", session.localPort)

        try {
            // then - the timed-out open is a failed handshake, not a cancellation of the dial
            assertThat(
                awaitTrue(STEADY_STATE_RETRY_TIMEOUT_MS) {
                    session.lastSuccessfulHandshakeNanos != null
                }
            ).isTrue()
            assertThat(openAttempts.get()).isGreaterThanOrEqualTo(2)
        } finally {
            releaseOpen.countDown()
            socket.close()
            session.close()
        }
    }

}
