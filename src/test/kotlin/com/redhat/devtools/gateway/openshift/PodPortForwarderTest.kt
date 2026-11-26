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
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Bridging and pump behaviour of [PodPortForwarder]. Retry, retarget and budget
 * behaviour lives in [PodPortForwarderRetryTest]; probe and bounded opens in
 * [PodPortForwarderProbeTest]; the dial state recovery observes in
 * [PodPortForwarderDialObservationTest].
 */
class PodPortForwarderTest : PodPortForwarderTestBase() {

    private val serverData = "from server"

    private val buffer = ByteArray(1024)

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
    fun `#forward closes the local socket when the remote side ends`() {
        // given - the remote stream reports EOF immediately (pod deleted)
        stubForward(forwardResult(ByteArrayInputStream(ByteArray(0))))

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

        // when - the session ends (teardown): the whole forward, listener included, is closed
        session.close()

        // then - a re-dial is refused instead of reaching a dead session
        assertThrows<IOException> {
            Socket("127.0.0.1", session.localPort).close()
        }
    }

    @Test
    fun `#dropClientConnections cuts the copied connections but keeps listening`() {
        // given - a bridged connection whose pod side stays silent, and the opens it took
        val opens = AtomicInteger()
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } answers {
            opens.incrementAndGet()
            silentForwardResult()
        }
        val remoteEnds = AtomicInteger()
        val session = PodPortForwarder(client)
            .forward(pod, remotePort) { remoteEnds.incrementAndGet() }
        val guest = Socket("127.0.0.1", session.localPort)
        assertThat(awaitTrue(EVENT_TIMEOUT_MS) { opens.get() == 1 }).isTrue()

        // when - the workspace stops: the Guest must read EOF at once, not when the pod terminates
        session.dropClientConnections()
        guest.soTimeout = EVENT_TIMEOUT_MS.toInt()
        assertThat(guest.inputStream.read()).isEqualTo(-1)
        guest.close()

        // then - the cut ends the copy like a pod-side loss: the episode trigger fires
        assertThat(awaitTrue(EVENT_TIMEOUT_MS) { remoteEnds.get() == 1 }).isTrue()

        // and - the listener still accepts and bridges the re-dial of a later forced reconnect
        Socket("127.0.0.1", session.localPort).use { redial ->
            assertThat(awaitTrue(EVENT_TIMEOUT_MS) { opens.get() == 2 }).isTrue()
        }
        session.close()
    }

    @Test
    fun `#forward reports a connection the pod side cut`() {
        // given - the remote stream reports EOF immediately (pod deleted)
        stubForward(forwardResult(ByteArrayInputStream(ByteArray(0))))
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
        stubForward(silentForwardResult())
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
        val portForwardResult = forwardResult(silentPodStream())
        every { portForwardResult.getOutboundStream(remotePort) } returns brokenTunnelStream()
        stubForward(portForwardResult)
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
        stubForward(forwardResult(ByteArrayInputStream(ByteArray(0))))
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
}
