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
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1Pod
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds

/** Opens a forward pinned to [pod], without following a replacement pod or observing remote end. */
@Throws(IOException::class)
internal fun PodPortForwarder.forward(pod: V1Pod, remotePort: Int): PortForwardSession =
    forward(pod, remotePort, { null }) {}

/** Opens a forward pinned to [pod] that reports a remote-side end via [onRemoteEnd]. */
@Throws(IOException::class)
internal fun PodPortForwarder.forward(
    pod: V1Pod,
    remotePort: Int,
    onRemoteEnd: () -> Unit
): PortForwardSession = forward(pod, remotePort, { null }, onRemoteEnd)

/**
 * Fixtures shared by the port-forward tests: the mocked cluster client, the pinned pod, and the
 * stream doubles that stand in for the pod side of a forward.
 */
abstract class PodPortForwarderTestBase {

    companion object {
        /** More consecutive failures than connect-time allows, so only steady-state can survive them. */
        internal const val ESTABLISHED_HANDSHAKE_FAILURES = 4

        /**
         * Covers the steady-state backoff after the failures above (1s + 2s + 3s + 5s) plus the
         * 100ms client-gone probes between attempts - and the full-suite load on top of that
         * real-time schedule, which alone has pushed this test past its old 25s window.
         */
        internal const val STEADY_STATE_RETRY_TIMEOUT_MS = 60_000L

        internal const val HANDSHAKE_TIMEOUT_MS = 5_000L

        /**
         * Scheduler slack added to every observation window. The port-forward dispatcher thread and
         * the polling test thread can both be stalled for seconds under full-suite load, so a
         * window sized to the event alone fails on the stall, not on the behaviour under test.
         */
        internal const val TIMING_SLACK_MS = 10_000L

        /** Window for an event that is expected within milliseconds (a handshake, a dial's end). */
        internal const val EVENT_TIMEOUT_MS = HANDSHAKE_TIMEOUT_MS + TIMING_SLACK_MS

        /** Probe bound of the hang test: far below the production 3s to keep the test fast. */
        internal const val PROBE_BOUND_MS = 100L

        /**
         * Bound of one port-forward open in the hang tests: production uses [OPEN_TIMEOUT_MS]
         * (10 s), far too long for a test that provokes the hang.
         */
        internal const val OPEN_BOUND_MS = 200L

        /** How long a bounded open may take to free its blocked thread after the bound fired. */
        internal const val OPEN_RELEASE_TIMEOUT_MS = 5_000L

        /**
         * Safety cap of the open that outlives [PROBE_BOUND_MS]. The test releases the open itself,
         * so the hang lasts until then: no scheduling delay can shorten the margin under test.
         */
        internal const val HUNG_OPEN_CAP_MS = 30_000L

        /**
         * Survival a dial needs to count as an established session. Production uses
         * [DIAL_CONFIRMATION_MS] (15s, more than twice the measured 6.5-6.9s of a stale-link dial);
         * the tests shrink it so both sides of the threshold are exercised with short dials.
         */
        internal const val TEST_CONFIRMATION_MS = 200L

        /** Keeps a dialed connection safely past the confirmation threshold, so it is long-lived. */
        internal const val LONG_LIVED_MARGIN_MS = 200L

        /**
         * Window for a dial to be classified as long-lived. Larger than the dial itself
         * ([TEST_CONFIRMATION_MS] + [LONG_LIVED_MARGIN_MS]) by [TIMING_SLACK_MS]: the dial ends with
         * a `Thread.sleep` on the dispatcher thread, which load can delay past the dial's own length.
         */
        internal const val LONG_LIVED_DIAL_TIMEOUT_MS =
            TEST_CONFIRMATION_MS + LONG_LIVED_MARGIN_MS + TIMING_SLACK_MS

        internal const val NANOS_PER_MILLI = 1_000_000L
    }

    protected lateinit var client: ApiClient

    protected val pod = podWith("luke-skywalker")

    protected val remotePort = 8080

    @BeforeEach
    fun beforeEach() {
        client = mockk(relaxed = true)
        mockkConstructor(PortForward::class)
    }

    @AfterEach
    fun afterEach() {
        unmockkAll()
    }

    /** Polls [condition] until it holds or [timeoutMs] elapses: the retry schedule is time-based. */
    protected fun awaitTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            runBlocking { delay(100.milliseconds) }
        }
        return condition()
    }

    /** A forward whose pod side stays silent: only closing the session ends the connection. */
    protected fun silentForwardResult(): PortForward.PortForwardResult =
        forwardResult(silentPodStream())

    /** A forward result whose pod stream is [podStream]; the outbound stream discards writes. */
    protected fun forwardResult(podStream: InputStream): PortForward.PortForwardResult {
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every { portForwardResult.getInputStream(remotePort) } returns podStream
        every { portForwardResult.getOutboundStream(remotePort) } returns ByteArrayOutputStream()
        return portForwardResult
    }

    /** Stubs every open of the forward against [pod] to return [result]. */
    protected fun stubForward(result: PortForward.PortForwardResult) {
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } returns result
    }

    /** Asserts the local socket is closed by the forward instead of left hanging. */
    protected fun assertSocketClosed(localPort: Int) {
        Socket("127.0.0.1", localPort).use { socket ->
            socket.soTimeout = EVENT_TIMEOUT_MS.toInt()
            assertThat(socket.inputStream.read()).isEqualTo(-1)
        }
    }

    /** The error client-java raises when the pod that served the forward is gone. */
    protected fun podGoneException(): ApiException =
        ApiException("Expected HTTP 101 response but was '404 Not Found'")

    /** Fails the pinned pod's open as gone; any other pod's open is answered by [onReplacementOpen]. */
    protected fun stubGonePinnedPod(onReplacementOpen: (V1Pod) -> PortForward.PortForwardResult) {
        every {
            anyConstructed<PortForward>().forward(any(), listOf(remotePort))
        } answers {
            if (firstArg<V1Pod>() === pod) throw podGoneException()
            onReplacementOpen(firstArg())
        }
    }

    /**
     * A forwarder whose liveness lets only [replacement] retry: the pinned pod counts as gone.
     * Callers open the forward with `{ replacement }` as its resolver.
     */
    internal fun replacementForwarder(
        replacement: V1Pod,
        nowNanos: () -> Long = System::nanoTime
    ): PodPortForwarder = PodPortForwarder(
        client,
        nowNanos = nowNanos,
        livenessProvider = { target ->
            if (target === replacement) PodLiveness.RUNNING else PodLiveness.GONE
        }
    )

    /**
     * A pod stream that blocks until it is closed, like a pod that stays silent. A bare
     * `PipedInputStream` cannot be used: unconnected, it fails with "Pipe not connected" instead
     * of blocking, and closing it unblocks a reader only after its 1s poll.
     */
    protected fun silentPodStream(): InputStream = object : InputStream() {
        private val released = CountDownLatch(1)

        override fun read(): Int {
            released.await()
            return -1
        }

        override fun close() {
            released.countDown()
        }
    }

    /** An outbound tunnel that fails on the first write, like a port forward whose pod just died. */
    protected fun brokenTunnelStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            throw IOException("broken pipe")
        }
    }

    /** The result a hanging open would produce if it ever finished: it never does, the bound wins. */
    protected fun hangingOpenResult(): PortForward.PortForwardResult =
        mockk(relaxed = true)

    /** A pod stream that reports EOF after [delayMs]: a dialed connection that ends by itself. */
    protected fun eofAfter(delayMs: Long): InputStream = object : InputStream() {
        override fun read(): Int {
            Thread.sleep(delayMs)
            return -1
        }
    }
}
