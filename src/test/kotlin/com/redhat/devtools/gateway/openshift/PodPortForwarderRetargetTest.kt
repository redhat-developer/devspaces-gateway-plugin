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

import com.redhat.devtools.gateway.testutil.podWith
import io.kubernetes.client.PortForward
import io.kubernetes.client.openapi.models.V1Pod
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Retargeting and outage-budget behaviour of [PodPortForwarder]: a gone pod is replaced by the
 * workspace's current pod, and the budget keeps running across the swap.
 */
class PodPortForwarderRetargetTest : PodPortForwarderTestBase() {

    private fun assertEndsWhenGonePodHasNoUsableReplacement(
        failingPod: V1Pod,
        resolve: () -> V1Pod?,
        terminalReason: String
    ) {
        every {
            anyConstructed<PortForward>().forward(failingPod, listOf(remotePort))
        } throws podGoneException()
        val resolveCalls = AtomicInteger()
        val forwarder = PodPortForwarder(
            client,
            livenessProvider = { PodLiveness.GONE }
        )
        val session = forwarder.forward(
            failingPod,
            remotePort,
            resolvePod = {
                resolveCalls.incrementAndGet()
                resolve()
            }
        ) {}
        try {
            runBlocking { delay(100.milliseconds) }

            // then - terminal: the accepted socket is closed after the single failing attempt
            assertSocketClosed(session.localPort)
            verify(exactly = 1) {
                anyConstructed<PortForward>().forward(failingPod, listOf(remotePort))
            }
            assertThat(resolveCalls.get()).describedAs(terminalReason).isEqualTo(1)
        } finally {
            session.close()
        }
    }

    /** A session already established against [replacement], with its clock at [clockNanos]. */
    private fun establishedSessionWithReplacement(
        replacement: V1Pod,
        clockNanos: AtomicLong
    ): PortForwardSession =
        replacementForwarder(replacement) { clockNanos.get() }
            .forward(pod, remotePort, resolvePod = { replacement }) {}
            .also { it.markEstablished() }

    @Test
    fun `#forward retargets to the pod that replaced a gone pod`() {
        // given - the pinned pod is gone, the workspace already runs a replacement
        val replacement = podWith("new-pod", uid = "new-uid")
        val portForwardResult = silentForwardResult()
        stubGonePinnedPod { portForwardResult }
        val forwarder = replacementForwarder(replacement)

        // when
        val session = forwarder.forward(pod, remotePort, resolvePod = { replacement }) {}
        try {
            Socket("127.0.0.1", session.localPort).use {
                // then - the retry lands on the replacement and the connection is established
                assertThat(
                    awaitTrue(EVENT_TIMEOUT_MS) { session.lastSuccessfulHandshakeNanos != null }
                ).isTrue()
            }
            verify(exactly = 1) {
                anyConstructed<PortForward>().forward(pod, listOf(remotePort))
            }
            verify(exactly = 1) {
                anyConstructed<PortForward>().forward(replacement, listOf(remotePort))
            }
        } finally {
            session.close()
        }
    }

    /**
     * Asserts a forward against a gone [failingPod] ends after its single failing attempt, with the
     * resolver asked once and no pointless swap loop.
     */
    @Test
    fun `#forward stops when the gone pod has no replacement`() {
        // given - the pod is gone and the workspace has no running pod to move to
        assertEndsWhenGonePodHasNoUsableReplacement(pod, { null }, "no replacement resolved twice")
    }

    @Test
    fun `#forward stops when the resolver still names the pod that just failed`() {
        // given - the resolver answers "the current pod is the one that just failed"
        val gonePod = podWith("luke-skywalker", uid = "old-uid")
        assertEndsWhenGonePodHasNoUsableReplacement(
            gonePod,
            { podWith("luke-skywalker", uid = "old-uid") },
            "the same pod must stay terminal"
        )
    }

    @Test
    fun `#forward retargets both connections of a session to one replacement`() {
        // given - two concurrent re-dials, both failing against the dead pod
        val replacement = podWith("new-pod", uid = "new-uid")
        val portForwardResult = silentForwardResult()
        val replacementAttempts = AtomicInteger()
        stubGonePinnedPod {
            replacementAttempts.incrementAndGet()
            portForwardResult
        }
        val resolveCalls = AtomicInteger()
        val forwarder = PodPortForwarder(
            client,
            livenessProvider = { PodLiveness.GONE }
        )
        val session = forwarder.forward(
            pod,
            remotePort,
            resolvePod = {
                resolveCalls.incrementAndGet()
                replacement
            }
        ) {}

        try {
            val first = Socket("127.0.0.1", session.localPort)
            val second = Socket("127.0.0.1", session.localPort)
            first.use {
                second.use {
                    // then - both connections end up bridged to the replacement
                    assertThat(
                        awaitTrue(EVENT_TIMEOUT_MS) {
                            session.hasActiveForward && replacementAttempts.get() >= 2
                        }
                    ).isTrue()
                }
            }
            // the dead pod is resolved at most once per connection and never in a loop: one swap
            // serves both connections
            assertThat(resolveCalls.get()).isBetween(1, 2)
            assertThat(replacementAttempts.get()).isEqualTo(2)
        } finally {
            session.close()
        }
    }

    @Test
    fun `#forward starts a fresh attempt series on the replacement pod`() {
        // given - a gone pod, and a replacement whose handshake always fails
        val replacement = podWith("new-pod", uid = "new-uid")
        val replacementAttempts = AtomicInteger()
        stubGonePinnedPod {
            replacementAttempts.incrementAndGet()
            throw IOException("connection reset")
        }
        val forwarder = replacementForwarder(replacement)

        // when
        val session = forwarder.forward(pod, remotePort, resolvePod = { replacement }) {}
        try {
            runBlocking { delay(100.milliseconds) }
            Socket("127.0.0.1", session.localPort).use { socket ->
                socket.soTimeout = EVENT_TIMEOUT_MS.toInt()

                // then - the connect-time series restarts at the replacement: the dead pod's
                // failure did not consume one of its attempts. Waiting for the series to have run
                // (rather than sleeping a fixed time) keeps this independent of scheduler load;
                // a fourth attempt would keep the socket open and fail the read below.
                assertThat(
                    awaitTrue(EVENT_TIMEOUT_MS) {
                        replacementAttempts.get() >= ForwardRetryPolicy.CONNECT_ATTEMPTS
                    }
                ).isTrue()
                assertThat(socket.inputStream.read()).isEqualTo(-1)
            }
            assertThat(replacementAttempts.get()).isEqualTo(ForwardRetryPolicy.CONNECT_ATTEMPTS)
            verify(exactly = 1) {
                anyConstructed<PortForward>().forward(pod, listOf(remotePort))
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `#forward stops retargeting once the established outage budget elapsed`() {
        // given - an established session whose first failure happened long before the retarget
        val replacement = podWith("new-pod", uid = "new-uid")
        val replacementAttempts = AtomicInteger()
        val clockNanos = AtomicLong(0)
        stubGonePinnedPod {
            replacementAttempts.incrementAndGet()
            // the replacement is reached only after the outage budget already elapsed
            clockNanos.addAndGet(ForwardRetryPolicy.STEADY_STATE_OUTAGE_BUDGET_MS * NANOS_PER_MILLI)
            throw IOException("connection reset")
        }
        val session = establishedSessionWithReplacement(replacement, clockNanos)
        try {
            runBlocking { delay(100.milliseconds) }

            // then - the budget keeps running across the retarget and wins over the fresh series:
            // the socket is closed after the first attempt on the replacement
            assertSocketClosed(session.localPort)
            assertThat(replacementAttempts.get()).isEqualTo(1)
        } finally {
            session.close()
        }
    }

    @Test
    fun `#forward keeps retrying a replacement within the established outage budget`() {
        // given - an established session early in its outage budget
        val replacement = podWith("new-pod", uid = "new-uid")
        val replacementAttempts = AtomicInteger()
        val clockNanos = AtomicLong(0)
        val portForwardResult = silentForwardResult()
        stubGonePinnedPod {
            clockNanos.addAndGet(NANOS_PER_MILLI * 60_000)
            if (replacementAttempts.incrementAndGet() == 1) {
                throw IOException("connection reset")
            }
            portForwardResult
        }
        val session = establishedSessionWithReplacement(replacement, clockNanos)
        try {
            Socket("127.0.0.1", session.localPort).use {
                // then - the retargeted series retries while the budget lasts and succeeds
                assertThat(
                    awaitTrue(EVENT_TIMEOUT_MS) { session.lastSuccessfulHandshakeNanos != null }
                ).isTrue()
            }
            assertThat(replacementAttempts.get()).isEqualTo(2)
        } finally {
            session.close()
        }
    }
}
