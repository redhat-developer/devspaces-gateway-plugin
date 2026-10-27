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
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class PodPortForwarderTest {

    private val serverData = "from server"

    private lateinit var client: ApiClient

    private lateinit var buffer: ByteArray

    private val pod = V1Pod().apply {
       metadata = V1ObjectMeta().apply {
            name = "luke-skywalker"
        }
    }

    private val remotePort = 8080
    private var localPort = 0

    @BeforeEach
    fun beforeEach() {
        client = mockk(relaxed = true)
        localPort = findFreePort()
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
        val closeable = PodPortForwarder(client).forward(pod, localPort, remotePort)

        // then
        // wait for the server to start
        runBlocking { delay(100.milliseconds) }

        closeable.use { closeable ->
            // Verify that data from server input stream is received by client
            val bytesRead = sendClientData("ping") // Send data to trigger server response
            assertThat(String(buffer, 0, bytesRead)).isEqualTo(serverData)
        }
    }

    @Test
    fun `#forward tries several times if connecting fails`() {
        // given
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } throws IOException("connection reset")
        val podsRunning = PodPortForwarder(client) { PodLiveness.RUNNING }

        // when
        val closeable = podsRunning.forward(pod, localPort, remotePort)

        // then
        // wait for the server to start
        runBlocking { delay(100.milliseconds) }
        // the client stays connected and the pod alive: the loop's own caps
        // (CONNECT_ATTEMPTS / retry budget) are what stop the retries
        val socket = Socket("127.0.0.1", localPort)
        try {
            // attempts land at ~0s, ~1.2s and ~2.4s (1s reconnect delay + 2x100ms client-gone
            // probes between attempts); attempt #3 is the last one CONNECT_ATTEMPTS allows, and
            // by then the 2s budget is exhausted too. Waiting 5s — past where a 4th attempt
            // (~3.6s) would land — proves the loop stopped rather than retried forever.
            runBlocking { delay(5000.milliseconds) }
            verify(atLeast = 2) { // 2+ retries
                anyConstructed<PortForward>().forward(pod, listOf(remotePort))
            }
            verify(atMost = 3) { // neither the attempts cap nor the budget allows a 4th attempt
                anyConstructed<PortForward>().forward(pod, listOf(remotePort))
            }
        } finally {
            socket.close()
            closeable.close()
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
        val closeable = podsRunning.forward(pod, localPort, remotePort)
        try {
            // wait for the server to start
            runBlocking { delay(100.milliseconds) }
            Socket("127.0.0.1", localPort).apply {
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
        val closeable = PodPortForwarder(client).forward(pod, localPort, remotePort)
        runBlocking { delay(100.milliseconds) }

        // then - the local socket is closed instead of staying open waiting for the other direction
        Socket("127.0.0.1", localPort).use { socket ->
            socket.soTimeout = 2000
            assertThat(socket.inputStream.read()).isEqualTo(-1)
        }
        closeable.close()
    }

    @Test
    fun `#close stops accepting connections`() {
        // given - a forward that is not bridging anything
        val session = PodPortForwarder(client).forward(pod, localPort, remotePort)

        // when - the workspace is stopped: the whole forward, listener included, is closed
        session.close()

        // then - a re-dial is refused instead of reaching the stopped pod
        assertThrows<IOException> {
            Socket("127.0.0.1", localPort).close()
        }
    }

    @Test
    fun `#forward does not retry when the pod is gone`() {
        // given
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } throws ApiException("Expected HTTP 101 response but was '404 Not Found'")
        val podsGone = PodPortForwarder(client) { PodLiveness.GONE }

        // when
        val closeable = podsGone.forward(pod, localPort, remotePort)
        runBlocking { delay(100.milliseconds) }

        // then - the accepted socket is closed by the first, failing attempt:
        // the client sees EOF instead of a connection left hanging
        Socket("127.0.0.1", localPort).use { socket ->
            socket.soTimeout = 2000
            assertThat(socket.inputStream.read()).isEqualTo(-1)
        }
        verify(exactly = 1) {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        }
        closeable.close()
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
        val closeable = podsRunning.forward(pod, localPort, remotePort)
        val socket = Socket("127.0.0.1", localPort)
        runBlocking { delay(500.milliseconds) }

        // then - the copy failure is terminal: one handshake, no liveness query, socket closed
        socket.soTimeout = 500
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
        val closeable = podsRunning.forward(pod, localPort, remotePort)
        Socket("127.0.0.1", localPort).use { socket ->
            socket.soTimeout = 2000
            assertThat(socket.inputStream.read()).isEqualTo(-1)
        }
        runBlocking { delay(100.milliseconds) }

        // then - a normal disconnect costs no Kubernetes API call
        assertThat(livenessCalls.get()).isZero()
        closeable.close()
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

    private fun podWith(name: String, namespace: String): V1Pod {
        return V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                this.name = name
                this.namespace = namespace
            }
        }
    }

    private fun sendClientData(data: String): Int {
        Socket("127.0.0.1", localPort).use {
            // client to server
            runCatching {
                it.outputStream.write(data.toByteArray())
                it.outputStream.flush()
            }

            // server to client
            return it.inputStream.read(buffer)
        }
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }
}
