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
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/** Opens a forward that does not observe remote end. */
@Throws(IOException::class)
private fun PortForwarder.forward(pod: V1Pod, remotePort: Int): PortForwardSession =
    forward(pod, remotePort) {}

@Suppress("LargeClass") // one forward, many retry/retarget cases; keeping them together eases discovery
class PodPortForwarderTest {

    companion object {
        /** More consecutive failures than connect-time allows, so only steady-state can survive them. */
        private const val ESTABLISHED_HANDSHAKE_FAILURES = 4

        /**
         * Covers the steady-state backoff after the failures above (1s + 2s + 3s + 5s) plus the
         * 100ms client-gone probes between attempts - and the full-suite load on top of that
         * real-time schedule, which alone has pushed this test past its old 25s window.
         */
        private const val STEADY_STATE_RETRY_TIMEOUT_MS = 60_000L

        private const val HANDSHAKE_TIMEOUT_MS = 5_000L

        /**
         * Scheduler slack added to every observation window. The port-forward dispatcher thread and
         * the polling test thread can both be stalled for seconds under full-suite load, so a
         * window sized to the event alone fails on the stall, not on the behaviour under test.
         */
        private const val TIMING_SLACK_MS = 10_000L

        /** Window for an event that is expected within milliseconds (a handshake, a dial's end). */
        private const val EVENT_TIMEOUT_MS = HANDSHAKE_TIMEOUT_MS + TIMING_SLACK_MS

        /** Probe bound of the hang test: far below the production 3s to keep the test fast. */
        private const val PROBE_BOUND_MS = 100L

        /**
         * Bound of one port-forward open in the hang tests: production uses [OPEN_TIMEOUT_MS]
         * (10 s), far too long for a test that provokes the hang.
         */
        private const val OPEN_BOUND_MS = 200L

        /** How long a bounded open may take to free its blocked thread after the bound fired. */
        private const val OPEN_RELEASE_TIMEOUT_MS = 5_000L

        /**
         * Safety cap of the open that outlives [PROBE_BOUND_MS]. The test releases the open itself,
         * so the hang lasts until then: no scheduling delay can shorten the margin under test.
         */
        private const val HUNG_OPEN_CAP_MS = 30_000L

        /**
         * Survival a dial needs to count as an established session. Production uses
         * [DIAL_CONFIRMATION_MS] (15s, more than twice the measured 6.5-6.9s of a stale-link dial);
         * the tests shrink it so both sides of the threshold are exercised with short dials.
         */
        private const val TEST_CONFIRMATION_MS = 200L

        /** Keeps a dialed connection safely past the confirmation threshold, so it is long-lived. */
        private const val LONG_LIVED_MARGIN_MS = 200L

        /**
         * Window for a dial to be classified as long-lived. Larger than the dial itself
         * ([TEST_CONFIRMATION_MS] + [LONG_LIVED_MARGIN_MS]) by [TIMING_SLACK_MS]: the dial ends with
         * a `Thread.sleep` on the dispatcher thread, which load can delay past the dial's own length.
         */
        private const val LONG_LIVED_DIAL_TIMEOUT_MS =
            TEST_CONFIRMATION_MS + LONG_LIVED_MARGIN_MS + TIMING_SLACK_MS

        private const val NANOS_PER_MILLI = 1_000_000L
    }

    private val serverData = "from server"

    private lateinit var client: ApiClient

    private lateinit var buffer: ByteArray

    private val pod = V1Pod().apply {
       metadata = V1ObjectMeta().apply {
            name = "luke-skywalker"
        }
    }

    private val remotePort = 8080

    @BeforeEach
    fun beforeEach() {
        client = mockk(relaxed = true)
        buffer = ByteArray(1024)
        mockkConstructor(PortForward::class)
    }

    @AfterEach
    fun afterEach() {
        unmockkAll()
    }

    @Test
    fun `#forward copies from server to client`() {
        // given
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } returns portForwardResult
        val serverIn = ByteArrayInputStream(serverData.toByteArray())
        every {
            portForwardResult.getInputStream(remotePort)
        } returns serverIn
        val serverOut = ByteArrayOutputStream()
        every {
            portForwardResult.getOutboundStream(remotePort)
        } returns serverOut

        // when
        val closeable = PodPortForwarder(client).forward(pod, remotePort)

        // then
        // wait for the server to start
        runBlocking { delay(100.milliseconds) }

        closeable.use { closeable ->
            // Verify that data from server input stream is received by client
            val bytesRead = sendClientData(closeable.localPort, "ping") // Send data to trigger server response
            assertThat(String(buffer, 0, bytesRead)).isEqualTo(serverData)
        }
    }

    @Test
    fun `#forward keeps the client payload written while the handshake retries`() {
        // given - the first open fails, the client writes during the retry delay, the next succeeds
        val payload = "ping-payload"
        val serverOut = ByteArrayOutputStream()
        val secondResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every { secondResult.getInputStream(remotePort) } returns silentPodStream()
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
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every { portForwardResult.getInputStream(remotePort) } returns silentPodStream()
        every { portForwardResult.getOutboundStream(remotePort) } returns ByteArrayOutputStream()
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
    fun `#markEstablished is idempotent and records the successful handshake`() {
        // given - a silent pod stream, so the handshake is the only observable event
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } returns portForwardResult
        every { portForwardResult.getInputStream(remotePort) } returns silentPodStream()
        every { portForwardResult.getOutboundStream(remotePort) } returns ByteArrayOutputStream()
        val session = PodPortForwarder(client).forward(pod, remotePort)

        try {
            // when - connect calls it once, a re-run may call it again
            assertThat(session.isEstablished).isFalse()
            session.markEstablished()
            session.markEstablished()

            // then - the phase only moves forward and the accepted connection records the handshake
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
    fun `#forward closes the local socket when the remote side ends`() {
        // given - the remote stream reports EOF immediately (pod deleted)
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } returns portForwardResult
        every { portForwardResult.getInputStream(remotePort) } returns ByteArrayInputStream(ByteArray(0))
        every { portForwardResult.getOutboundStream(remotePort) } returns ByteArrayOutputStream()

        // when
        val closeable = PodPortForwarder(client).forward(pod, remotePort)
        runBlocking { delay(100.milliseconds) }

        // then - the local socket is closed instead of staying open waiting for the other direction
        Socket("127.0.0.1", closeable.localPort).use { socket ->
            socket.soTimeout = EVENT_TIMEOUT_MS.toInt()
            assertThat(socket.inputStream.read()).isEqualTo(-1)
        }
        closeable.close()
    }

    @Test
    fun `#close stops accepting connections`() {
        // given - a forward that is not bridging anything
        val session = PodPortForwarder(client).forward(pod, remotePort)

        // when - the workspace is stopped: the whole forward, listener included, is closed
        session.close()

        // then - a re-dial is refused instead of reaching the stopped pod
        assertThrows<IOException> {
            Socket("127.0.0.1", session.localPort).close()
        }
    }

    @Test
    fun `#forward reports a connection the pod side cut`() {
        // given - the remote stream reports EOF immediately (pod deleted)
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } returns portForwardResult
        every { portForwardResult.getInputStream(remotePort) } returns ByteArrayInputStream(ByteArray(0))
        every { portForwardResult.getOutboundStream(remotePort) } returns ByteArrayOutputStream()
        val remoteEnds = AtomicInteger()

        // when
        val session = PodPortForwarder(client)
            .forward(pod, remotePort) { remoteEnds.incrementAndGet() }
        runBlocking { delay(100.milliseconds) }
        Socket("127.0.0.1", session.localPort).use { socket ->
            socket.soTimeout = EVENT_TIMEOUT_MS.toInt()
            assertThat(socket.inputStream.read()).isEqualTo(-1)
        }

        // then - the pod-side end is reported, however late the load lets the pump finish
        assertThat(awaitTrue(EVENT_TIMEOUT_MS) { remoteEnds.get() == 1 }).isTrue()
        session.close()
    }

    @Test
    fun `#forward does not report a connection the client closed itself`() {
        // given - the pod stream never ends: only the client can end the connection
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } returns portForwardResult
        every { portForwardResult.getInputStream(remotePort) } returns silentPodStream()
        every { portForwardResult.getOutboundStream(remotePort) } returns ByteArrayOutputStream()
        val remoteEnds = AtomicInteger()

        // when
        val session = PodPortForwarder(client)
            .forward(pod, remotePort) { remoteEnds.incrementAndGet() }
        runBlocking { delay(100.milliseconds) }
        Socket("127.0.0.1", session.localPort).close() // the Guest closed its window
        runBlocking { delay(300.milliseconds) }

        // then - the client is gone, its window must not be pulled to the front
        assertThat(remoteEnds.get()).isZero()
        session.close()
    }

    @Test
    fun `#forward reports a connection whose client pump failed`() {
        // given - a messy pod death: the write into the dying tunnel fails before the pod stream
        // reports EOF, so the client pump is the first to end the copy
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } returns portForwardResult
        every { portForwardResult.getInputStream(remotePort) } returns silentPodStream()
        every { portForwardResult.getOutboundStream(remotePort) } returns brokenTunnelStream()
        val remoteEnds = AtomicInteger()

        // when
        val session = PodPortForwarder(client)
            .forward(pod, remotePort) { remoteEnds.incrementAndGet() }
        runBlocking { delay(100.milliseconds) }
        Socket("127.0.0.1", session.localPort).use { socket ->
            // the copy may already have closed the socket: the client is not what ends it
            runCatching {
                socket.outputStream.write("ping".toByteArray())
                socket.outputStream.flush()
            }
            assertThat(awaitTrue(EVENT_TIMEOUT_MS) { remoteEnds.get() == 1 }).isTrue()
        }

        // then - only a clean client-side close suppresses the report
        assertThat(remoteEnds.get()).isEqualTo(1)
        session.close()
    }

    @Test
    fun `#forward does not retry when the pod is gone`() {
        // given
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } throws ApiException("Expected HTTP 101 response but was '404 Not Found'")
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

    @Test
    fun `#forward retargets to the pod that replaced a gone pod`() {
        // given - the pinned pod is gone, the workspace already runs a replacement
        val replacement = podWith("new-pod", uid = "new-uid")
        val portForwardResult = silentForwardResult()
        every {
            anyConstructed<PortForward>().forward(any(), listOf(remotePort))
        } answers {
            if (firstArg<V1Pod>() === pod) {
                throw ApiException("Expected HTTP 101 response but was '404 Not Found'")
            }
            portForwardResult
        }
        val forwarder = PodPortForwarder(
            client,
            resolvePod = { replacement },
            livenessProvider = { target ->
                if (target === replacement) PodLiveness.RUNNING else PodLiveness.GONE
            }
        )

        // when
        val session = forwarder.forward(pod, remotePort)
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

    @Test
    fun `#forward stops when the gone pod has no replacement`() {
        // given - the pod is gone and the workspace has no running pod to move to
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } throws ApiException("Expected HTTP 101 response but was '404 Not Found'")
        val resolveCalls = AtomicInteger()
        val forwarder = PodPortForwarder(
            client,
            resolvePod = {
                resolveCalls.incrementAndGet()
                null
            },
            livenessProvider = { PodLiveness.GONE }
        )

        // when
        val session = forwarder.forward(pod, remotePort)
        try {
            runBlocking { delay(100.milliseconds) }

            // then - terminal: the accepted socket is closed after the single failing attempt
            Socket("127.0.0.1", session.localPort).use { socket ->
                socket.soTimeout = EVENT_TIMEOUT_MS.toInt()
                assertThat(socket.inputStream.read()).isEqualTo(-1)
            }
            verify(exactly = 1) {
                anyConstructed<PortForward>().forward(pod, listOf(remotePort))
            }
            assertThat(resolveCalls.get()).isEqualTo(1)
        } finally {
            session.close()
        }
    }

    @Test
    fun `#forward stops when the resolver still names the pod that just failed`() {
        // given - the resolver answers "the current pod is the one that just failed"
        val gonePod = podWith("luke-skywalker", uid = "old-uid")
        every {
            anyConstructed<PortForward>().forward(gonePod, listOf(remotePort))
        } throws ApiException("Expected HTTP 101 response but was '404 Not Found'")
        val resolveCalls = AtomicInteger()
        val forwarder = PodPortForwarder(
            client,
            resolvePod = {
                resolveCalls.incrementAndGet()
                podWith("luke-skywalker", uid = "old-uid")
            },
            livenessProvider = { PodLiveness.GONE }
        )

        // when
        val session = forwarder.forward(gonePod, remotePort)
        try {
            runBlocking { delay(100.milliseconds) }

            // then - no pointless swap loop: the same pod stays terminal
            Socket("127.0.0.1", session.localPort).use { socket ->
                socket.soTimeout = EVENT_TIMEOUT_MS.toInt()
                assertThat(socket.inputStream.read()).isEqualTo(-1)
            }
            verify(exactly = 1) {
                anyConstructed<PortForward>().forward(gonePod, listOf(remotePort))
            }
            assertThat(resolveCalls.get()).isEqualTo(1)
        } finally {
            session.close()
        }
    }

    @Test
    fun `#forward retargets once for two connections that see the same gone pod`() {
        // given - two concurrent re-dials, both failing against the dead pod
        val replacement = podWith("new-pod", uid = "new-uid")
        val portForwardResult = silentForwardResult()
        val replacementAttempts = AtomicInteger()
        every {
            anyConstructed<PortForward>().forward(any(), listOf(remotePort))
        } answers {
            if (firstArg<V1Pod>() === pod) {
                throw ApiException("Expected HTTP 101 response but was '404 Not Found'")
            }
            replacementAttempts.incrementAndGet()
            portForwardResult
        }
        val resolveCalls = AtomicInteger()
        val forwarder = PodPortForwarder(
            client,
            resolvePod = {
                resolveCalls.incrementAndGet()
                replacement
            },
            livenessProvider = { PodLiveness.GONE }
        )
        val session = forwarder.forward(pod, remotePort)

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
            // the dead pod is resolved at most once per connection: one swap serves both
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
        every {
            anyConstructed<PortForward>().forward(any(), listOf(remotePort))
        } answers {
            if (firstArg<V1Pod>() === pod) {
                throw ApiException("Expected HTTP 101 response but was '404 Not Found'")
            }
            replacementAttempts.incrementAndGet()
            throw IOException("connection reset")
        }
        val forwarder = PodPortForwarder(
            client,
            resolvePod = { replacement },
            livenessProvider = { target ->
                if (target === replacement) PodLiveness.RUNNING else PodLiveness.GONE
            }
        )

        // when
        val session = forwarder.forward(pod, remotePort)
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
        every {
            anyConstructed<PortForward>().forward(any(), listOf(remotePort))
        } answers {
            if (firstArg<V1Pod>() === pod) {
                throw ApiException("Expected HTTP 101 response but was '404 Not Found'")
            }
            replacementAttempts.incrementAndGet()
            // the replacement is reached only after the outage budget already elapsed
            clockNanos.addAndGet(ForwardRetryPolicy.STEADY_STATE_OUTAGE_BUDGET_MS * NANOS_PER_MILLI)
            throw IOException("connection reset")
        }
        val forwarder = PodPortForwarder(
            client,
            resolvePod = { replacement },
            nowNanos = { clockNanos.get() },
            livenessProvider = { target ->
                if (target === replacement) PodLiveness.RUNNING else PodLiveness.GONE
            }
        )

        // when
        val session = forwarder.forward(pod, remotePort)
        session.markEstablished()
        try {
            runBlocking { delay(100.milliseconds) }

            // then - the budget keeps running across the retarget and wins over the fresh series:
            // the socket is closed after the first attempt on the replacement
            Socket("127.0.0.1", session.localPort).use { socket ->
                socket.soTimeout = EVENT_TIMEOUT_MS.toInt()
                assertThat(socket.inputStream.read()).isEqualTo(-1)
            }
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
        every {
            anyConstructed<PortForward>().forward(any(), listOf(remotePort))
        } answers {
            if (firstArg<V1Pod>() === pod) {
                throw ApiException("Expected HTTP 101 response but was '404 Not Found'")
            }
            clockNanos.addAndGet(NANOS_PER_MILLI * 60_000)
            if (replacementAttempts.incrementAndGet() == 1) {
                throw IOException("connection reset")
            }
            portForwardResult
        }
        val forwarder = PodPortForwarder(
            client,
            resolvePod = { replacement },
            nowNanos = { clockNanos.get() },
            livenessProvider = { target ->
                if (target === replacement) PodLiveness.RUNNING else PodLiveness.GONE
            }
        )

        // when
        val session = forwarder.forward(pod, remotePort)
        session.markEstablished()
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

    @Test
    fun `#forward does not retry when the copy fails mid session`() {
        // given - the pod stays alive, but the remote stream fails after the handshake
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } returns portForwardResult
        every { portForwardResult.getInputStream(remotePort) } throws IOException("connection reset")
        every { portForwardResult.getOutboundStream(remotePort) } returns ByteArrayOutputStream()
        val livenessCalls = AtomicInteger()
        val podsRunning = PodPortForwarder(client) {
            livenessCalls.incrementAndGet()
            PodLiveness.RUNNING
        }

        // when
        val closeable = podsRunning.forward(pod, remotePort)
        val socket = Socket("127.0.0.1", closeable.localPort)

        // then - the copy failure is terminal: one handshake, no liveness query, socket closed.
        // The read itself waits for that close (bounded), so no fixed sleep is needed.
        socket.soTimeout = EVENT_TIMEOUT_MS.toInt()
        val readOutcome = runCatching { socket.inputStream.read() }
        assertThat(readOutcome.exceptionOrNull() is SocketTimeoutException).isFalse()
        assertThat(readOutcome.getOrNull() ?: -1).isEqualTo(-1)
        verify(exactly = 1) {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        }
        assertThat(livenessCalls.get()).isZero()
        socket.close()
        closeable.close()
    }

    @Test
    fun `#forward does not query liveness on normal disconnect`() {
        // given - the remote stream reports EOF immediately (normal end of the connection)
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } returns portForwardResult
        every { portForwardResult.getInputStream(remotePort) } returns ByteArrayInputStream(ByteArray(0))
        every { portForwardResult.getOutboundStream(remotePort) } returns ByteArrayOutputStream()
        val livenessCalls = AtomicInteger()
        val podsRunning = PodPortForwarder(client) {
            livenessCalls.incrementAndGet()
            PodLiveness.RUNNING
        }

        // when
        val closeable = podsRunning.forward(pod, remotePort)
        Socket("127.0.0.1", closeable.localPort).use { socket ->
            socket.soTimeout = EVENT_TIMEOUT_MS.toInt()
            assertThat(socket.inputStream.read()).isEqualTo(-1)
        }
        runBlocking { delay(100.milliseconds) }

        // then - a normal disconnect costs no Kubernetes API call
        assertThat(livenessCalls.get()).isZero()
        closeable.close()
    }

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
        val forwarder = PodPortForwarder(
            client,
            probeTimeoutMs = PROBE_BOUND_MS,
            openForward = { _, _ ->
                try {
                    releaseOpen.await(HUNG_OPEN_CAP_MS, TimeUnit.MILLISECONDS)
                    hangingOpenResult()
                } finally {
                    openFinished.countDown()
                }
            }
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
        val forwarder = PodPortForwarder(
            client,
            openTimeoutMs = OPEN_BOUND_MS,
            openForward = { _, _ ->
                openAttempts.incrementAndGet()
                // every open blocks until its bound interrupts it, like the stalled websocket
                releaseOpen.await(HUNG_OPEN_CAP_MS, TimeUnit.MILLISECONDS)
                hangingResult
            }
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
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every { portForwardResult.getInputStream(remotePort) } returns silentPodStream()
        every { portForwardResult.getOutboundStream(remotePort) } returns ByteArrayOutputStream()
        val releaseOpen = CountDownLatch(1)
        val openAttempts = AtomicInteger()
        val forwarder = PodPortForwarder(
            client,
            openTimeoutMs = OPEN_BOUND_MS,
            openForward = { _, _ ->
                if (openAttempts.incrementAndGet() == 1) {
                    try {
                        releaseOpen.await(HUNG_OPEN_CAP_MS, TimeUnit.MILLISECONDS)
                    } finally {
                        releaseOpen.countDown()
                    }
                }
                portForwardResult
            }
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

    @Test
    fun `#forward reports an active forward while a connection is copied`() {
        // given - a pod stream that never ends: only closing the session ends the connection
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } returns portForwardResult
        every { portForwardResult.getInputStream(remotePort) } returns silentPodStream()
        every { portForwardResult.getOutboundStream(remotePort) } returns ByteArrayOutputStream()
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
            assertThat(session.oldestActiveHandshakeNanos)
                .isEqualTo(session.lastSuccessfulHandshakeNanos)
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
    fun `#forward classifies a dial that ended before confirmation as failed`() {
        // given - the pod ends the connection well within the confirmation threshold, like the
        // 6.5-6.9s stale-link dials measured against the Guest's 6s transport timeout
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } returns portForwardResult
        every {
            portForwardResult.getInputStream(remotePort)
        } returns eofAfter(TEST_CONFIRMATION_MS / 4)
        every { portForwardResult.getOutboundStream(remotePort) } returns ByteArrayOutputStream()
        val session = PodPortForwarder(client, confirmationMs = TEST_CONFIRMATION_MS)
            .forward(pod, remotePort)

        try {
            // when
            Socket("127.0.0.1", session.localPort).use {
                assertThat(
                    awaitTrue(EVENT_TIMEOUT_MS) {
                        session.lastFailedDialNanos != null
                    }
                ).isTrue()
            }

            // then
            assertThat(session.failedDials).isEqualTo(1)
            assertThat(session.lastLongLivedDialNanos).isNull()
        } finally {
            session.close()
        }
    }

    @Test
    fun `#forward classifies a dial that outlived confirmation as long-lived`() {
        // given - the pod holds the connection past the confirmation threshold, like a real session
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } returns portForwardResult
        every {
            portForwardResult.getInputStream(remotePort)
        } returns eofAfter(TEST_CONFIRMATION_MS + LONG_LIVED_MARGIN_MS)
        every { portForwardResult.getOutboundStream(remotePort) } returns ByteArrayOutputStream()
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
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every { portForwardResult.getInputStream(remotePort) } returns silentPodStream()
        every { portForwardResult.getOutboundStream(remotePort) } returns ByteArrayOutputStream()
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

    @Test
    fun `#check returns GONE when the pod is not found`() = runBlocking {
        // given
        mockkConstructor(CoreV1Api::class)
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("missing-pod", "test-ns")
        } returns mockk { every { execute() } throws ApiException(404, "Not Found") }

        // when
        val liveness = PodLivenessProbe(client).query(podWith(name = "missing-pod", namespace = "test-ns"))

        // then
        assertThat(liveness).isEqualTo(PodLiveness.GONE)
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#check returns GONE when a running pod is terminating`() = runBlocking {
        // given
        mockkConstructor(CoreV1Api::class)
        val terminating = V1Pod().apply {
            metadata = V1ObjectMeta().apply { deletionTimestamp = OffsetDateTime.now() }
            status = V1PodStatus().apply { phase = "Running" }
        }
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("terminating-pod", "test-ns")
        } returns mockk { every { execute() } returns terminating }

        // when
        val liveness = PodLivenessProbe(client).query(podWith(name = "terminating-pod", namespace = "test-ns"))

        // then
        assertThat(liveness).isEqualTo(PodLiveness.GONE)
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#check returns GONE when a pod has a terminal phase`() = runBlocking {
        // given - Failed/Succeeded pods will not serve the forward anymore
        mockkConstructor(CoreV1Api::class)
        val failed = V1Pod().apply {
            status = V1PodStatus().apply { phase = "Failed" }
        }
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("failed-pod", "test-ns")
        } returns mockk { every { execute() } returns failed }

        // when
        val liveness = PodLivenessProbe(client).query(podWith(name = "failed-pod", namespace = "test-ns"))

        // then
        assertThat(liveness).isEqualTo(PodLiveness.GONE)
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#check returns UNKNOWN when the API call fails`() = runBlocking {
        // given
        mockkConstructor(CoreV1Api::class)
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("unreachable-pod", "test-ns")
        } returns mockk { every { execute() } throws IOException("connection refused") }

        // when
        val liveness = PodLivenessProbe(client).query(podWith(name = "unreachable-pod", namespace = "test-ns"))

        // then
        assertThat(liveness).isEqualTo(PodLiveness.UNKNOWN)
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#check returns UNKNOWN when a pod is pending`() = runBlocking {
        // given - during a pod recreate race the same pod name can briefly be Pending
        mockkConstructor(CoreV1Api::class)
        val pending = V1Pod().apply {
            status = V1PodStatus().apply { phase = "Pending" }
        }
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("pending-pod", "test-ns")
        } returns mockk { every { execute() } returns pending }

        // when
        val liveness = PodLivenessProbe(client).query(podWith(name = "pending-pod", namespace = "test-ns"))

        // then
        assertThat(liveness).isEqualTo(PodLiveness.UNKNOWN)
        unmockkConstructor(CoreV1Api::class)
    }

    /** Polls [condition] until it holds or [timeoutMs] elapses: the retry schedule is time-based. */
    private fun awaitTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            runBlocking { delay(100.milliseconds) }
        }
        return condition()
    }

    private fun podWith(name: String, namespace: String? = "test-ns", uid: String? = null): V1Pod {
        return V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                this.name = name
                this.namespace = namespace
                this.uid = uid
            }
        }
    }

    /** A forward whose pod side stays silent: only closing the session ends the connection. */
    private fun silentForwardResult(): PortForward.PortForwardResult {
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every { portForwardResult.getInputStream(remotePort) } returns silentPodStream()
        every { portForwardResult.getOutboundStream(remotePort) } returns ByteArrayOutputStream()
        return portForwardResult
    }

    private fun sendClientData(port: Int, data: String): Int {
        Socket("127.0.0.1", port).use {
            // client to server
            runCatching {
                it.outputStream.write(data.toByteArray())
                it.outputStream.flush()
            }

            // server to client
            return it.inputStream.read(buffer)
        }
    }

    /**
     * A pod stream that blocks until it is closed, like a pod that stays silent. A bare
     * `PipedInputStream` cannot be used: unconnected, it fails with "Pipe not connected" instead
     * of blocking, and closing it unblocks a reader only after its 1s poll.
     */
    private fun silentPodStream(onRead: () -> Unit = {}): InputStream = object : InputStream() {
        private val released = CountDownLatch(1)

        override fun read(): Int {
            onRead()
            released.await()
            return -1
        }

        override fun close() {
            released.countDown()
        }
    }

    /** An outbound tunnel that fails on the first write, like a port forward whose pod just died. */
    private fun brokenTunnelStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            throw IOException("broken pipe")
        }
    }

    /** The result a hanging open would produce if it ever finished: it never does, the bound wins. */
    private fun hangingOpenResult(): PortForward.PortForwardResult =
        mockk(relaxed = true)

    /** A pod stream that reports EOF after [delayMs]: a dialed connection that ends by itself. */
    private fun eofAfter(delayMs: Long): InputStream = object : InputStream() {
        override fun read(): Int {
            Thread.sleep(delayMs)
            return -1
        }
    }
}
