/*
 * Copyright (c) 2025 Red Hat, Inc.
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

import com.redhat.devtools.gateway.openshift.PodForwardResolution
import io.kubernetes.client.PortForward
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ListMeta
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodCondition
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.openapi.models.V1PodStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds

class DevWorkspacePodsTest {

    private val serverData = "from server"

    private lateinit var client: ApiClient
    private lateinit var pods: DevWorkspacePods

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
        pods = DevWorkspacePods(client)
        localPort = findFreePort()
        buffer = ByteArray(1024)
        mockkConstructor(PortForward::class)
    }

    @AfterEach
    fun afterEach() {
        unmockkConstructor(PortForward::class)
    }
    
    @Test
    fun `#forward copies from server to client`() {
        // given
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        val connectionReady = CompletableDeferred<Unit>()
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } answers {
            connectionReady.complete(Unit)
            portForwardResult
        }
        val serverIn = ByteArrayInputStream(serverData.toByteArray())
        every {
            portForwardResult.getInputStream(remotePort)
        } returns serverIn
        val serverOut = ByteArrayOutputStream()
        every {
            portForwardResult.getOutboundStream(remotePort)
        } returns serverOut

        // when
        val closeable = forwardPod()

        // then — keep socket open for data exchange, then close
        runBlocking {
            val socket = Socket("127.0.0.1", localPort)
            connectionReady.await()
            socket.outputStream.write("ping".toByteArray())
            socket.outputStream.flush()
            val bytesRead = socket.inputStream.read(buffer)
            assertThat(String(buffer, 0, bytesRead)).isEqualTo(serverData)
            socket.close()
        }

        closeable.use {}
    }

    @Test
    fun `#forward tries several times if connecting fails`() {
        // given
        val forwardCount = AtomicInteger(0)
        val handlerFinished = CompletableDeferred<Unit>()
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } answers {
            forwardCount.incrementAndGet()
            if (forwardCount.get() >= 2) handlerFinished.complete(Unit)
            throw IOException("unavailable")
        }

        // when
        val closeable = pods.forward(
            resolvePod = {
                if (forwardCount.get() >= 2) throw SignalExit() else PodForwardResolution(pod)
            },
            localPort = localPort,
            remotePort = remotePort,
            reconnectTimeoutSeconds = 30,
            reconnectDelaySeconds = 1,
        )

        runBlocking {
            delay(100.milliseconds)
            Socket("127.0.0.1", localPort).use { it.close() }
            handlerFinished.await()
        }

        // then
        closeable.use {
            verify(atLeast = 2) {
                anyConstructed<PortForward>().forward(pod, listOf(remotePort))
            }
        }
    }

    @Test
    fun `#forward reconnects to new pod after stream failure`() {
        // given
        val podA = podNamed("pod-a")
        val podB = podNamed("pod-b")
        var resolveCall = 0
        val connectionReady = CompletableDeferred<Unit>()
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every {
            portForwardResult.getInputStream(remotePort)
        } answers {
            connectionReady.complete(Unit) // signal that successful forward is established
            ByteArrayInputStream(serverData.toByteArray())
        }
        every {
            portForwardResult.getOutboundStream(remotePort)
        } returns ByteArrayOutputStream()
        every {
            anyConstructed<PortForward>().forward(any(), listOf(remotePort))
        } answers {
            when ((args[0] as V1Pod).metadata?.name) {
                "pod-a" -> throw IOException("pod unavailable")
                else -> portForwardResult
            }
        }

        // when — cap resolveCall so all connections after first get podB
        val closeable = pods.forward(
            resolvePod = {
                when (resolveCall++) {
                    0 -> PodForwardResolution(podA)
                    else -> PodForwardResolution(podB)
                }
            },
            localPort = localPort,
            remotePort = remotePort,
            reconnectTimeoutSeconds = 10,
            reconnectDelaySeconds = 1,
        )

        // Connect client, wait for forward to succeed, verify data flow, then close
        runBlocking {
            val socket = Socket("127.0.0.1", localPort)
            connectionReady.await() // waits until forward succeeds (podB)
            // Send data and read response
            socket.outputStream.write("ping".toByteArray())
            socket.outputStream.flush()
            val bytesRead = socket.inputStream.read(buffer)
            assertThat(String(buffer, 0, bytesRead)).isEqualTo(serverData)
            socket.close()
        }

        // then
        closeable.use {
            verify(atLeast = 2) {
                anyConstructed<PortForward>().forward(any(), listOf(remotePort))
            }
        }
    }

    @Test
    fun `#forward stops retrying after reconnectTimeoutSeconds`() {
        // given — forward mock signals after 2 calls; fetchPod exits on signal
        val forwardCount = AtomicInteger(0)
        val stopRetry = CompletableDeferred<Unit>()
        val handlerFinished = CompletableDeferred<Unit>()

        every {
            anyConstructed<PortForward>().forward(any(), listOf(remotePort))
        } answers {
            val count = forwardCount.incrementAndGet()
            if (count >= 2 && !handlerFinished.isCompleted) handlerFinished.complete(Unit)
            throw IOException("unavailable")
        }

        val closeable = pods.forward(
            resolvePod = {
                if (stopRetry.isCompleted) throw SignalExit() else PodForwardResolution(pod)
            },
            localPort = localPort,
            remotePort = remotePort,
            reconnectTimeoutSeconds = 4,
            reconnectDelaySeconds = 1,
        )

        runBlocking {
            delay(100.milliseconds)
            Socket("127.0.0.1", localPort).use { it.close() }
            handlerFinished.await() // wait for 2 forward calls
            stopRetry.complete(Unit) // signal fetchPod to exit on next retry
            delay(2500.milliseconds) // let retry loop process the signal (delay + fetchPod)
        }

        // then — retries bounded by timeout (2 attempts with 4s timeout / 2s delay)
        closeable.use {
            assertThat(forwardCount.get()).isLessThanOrEqualTo(2)
            verify(atMost = 2) {
                anyConstructed<PortForward>().forward(any(), listOf(remotePort))
            }
        }
    }

    @Test
    fun `#forward stops retrying when client disconnects`() {
        // given — signal after first forward call, then fetchPod exits on next retry
        val firstForwardDone = CompletableDeferred<Unit>()
        every {
            anyConstructed<PortForward>().forward(any(), listOf(remotePort))
        } answers {
            if (!firstForwardDone.isCompleted) firstForwardDone.complete(Unit)
            throw IOException("unavailable")
        }

        val closeable = pods.forward(
            resolvePod = {
                if (firstForwardDone.isCompleted) throw SignalExit() else PodForwardResolution(pod)
            },
            localPort = localPort,
            remotePort = remotePort,
            reconnectTimeoutSeconds = 30,
            reconnectDelaySeconds = 1,
        )

        runBlocking {
            Socket("127.0.0.1", localPort).use { it.close() }
            firstForwardDone.await() // wait for first forward call
            delay(3000.milliseconds) // let retry loop process signal (delay + fetchPod)
        }

        // then
        closeable.use {
            verify(exactly = 1) {
                anyConstructed<PortForward>().forward(any(), listOf(remotePort))
            }
        }
    }

    @Test
    fun `#forward uses retry delay from PodForwardResolution when pod is unavailable`() {
        val resolveCount = AtomicInteger(0)
        val closeable = pods.forward(
            resolvePod = {
                resolveCount.incrementAndGet()
                PodForwardResolution(null, retryDelaySeconds = 1)
            },
            localPort = localPort,
            remotePort = remotePort,
            reconnectTimeoutSeconds = 3,
            reconnectDelaySeconds = 5,
        )

        runBlocking {
            delay(100.milliseconds)
            Socket("127.0.0.1", localPort).use { it.close() }
            delay(2500.milliseconds)
        }

        closeable.use {
            assertThat(resolveCount.get()).isGreaterThanOrEqualTo(2)
        }
    }

    private fun forwardPod(reconnectTimeoutSeconds: Long = 30L): Closeable =
        pods.forward({ PodForwardResolution(pod) }, localPort, remotePort, reconnectTimeoutSeconds, reconnectDelaySeconds = 1)

    /** Exception used by tests to signal the retry loop to exit deterministically. */
    private class SignalExit : RuntimeException()

    private fun podNamed(name: String): V1Pod = V1Pod().apply {
        metadata = V1ObjectMeta().apply { this.name = name }
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

    private fun runningReadyPod(name: String, creationTimestamp: String): V1Pod = V1Pod().apply {
        metadata = V1ObjectMeta().apply {
            this.name = name
            this.creationTimestamp = OffsetDateTime.parse(creationTimestamp)
        }
        status = V1PodStatus().apply {
            phase = "Running"
            conditions = listOf(
                V1PodCondition().apply {
                    type = "Ready"
                    status = "True"
                }
            )
        }
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private fun createPodList(vararg podNames: String): V1PodList {
        return V1PodList().apply {
            metadata = V1ListMeta()
            items = podNames.map { name ->
                V1Pod().apply {
                    metadata = V1ObjectMeta().apply {
                        this.name = name
                    }
                }
            }
        }
    }

    @Test
    fun `#waitForPodsDeleted returns true when pods are already deleted`() = runBlocking {
        // given
        val namespace = "test-namespace"
        val workspaceName = "test-workspace"
        val timeout = 5L

        mockkConstructor(CoreV1Api::class)
        val emptyPodList = createPodList()

        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(namespace)
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } returns emptyPodList
            }
        }

        // when
        val result = pods.waitForPodsDeleted(namespace, workspaceName, timeout)

        // then
        assertThat(result).isTrue()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#waitForPodsDeleted returns true when pods get deleted during waiting`() = runBlocking {
        // given
        val namespace = "test-namespace"
        val workspaceName = "test-workspace"
        val timeout = 10L

        mockkConstructor(CoreV1Api::class)
        val podWithItems = createPodList("pod-1")
        val emptyPodList = createPodList()

        var callCount = 0
        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(namespace)
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } answers {
                    callCount++
                    if (callCount <= 2) podWithItems else emptyPodList
                }
            }
        }

        // when
        val result = pods.waitForPodsDeleted(namespace, workspaceName, timeout)

        // then
        assertThat(result).isTrue()
        assertThat(callCount).isGreaterThan(2)
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#waitForPodsDeleted returns false when timeout is reached`() = runBlocking {
        // given
        val namespace = "test-namespace"
        val workspaceName = "test-workspace"
        val timeout = 2L // short timeout

        mockkConstructor(CoreV1Api::class)
        val podWithItems = createPodList("pod-1")

        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(namespace)
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } returns podWithItems
            }
        }

        // when
        val result = pods.waitForPodsDeleted(namespace, workspaceName, timeout)

        // then
        assertThat(result).isFalse()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#waitForPodsDeleted uses correct label selector`() = runBlocking {
        // given
        val namespace = "test-namespace"
        val workspaceName = "my-workspace"
        val timeout = 5L

        mockkConstructor(CoreV1Api::class)
        val labelSelectorSlot = slot<String>()
        val emptyPodList = createPodList()

        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(namespace)
        } returns mockk {
            every { labelSelector(capture(labelSelectorSlot)) } returns mockk {
                every { execute() } returns emptyPodList
            }
        }

        // when
        pods.waitForPodsDeleted(namespace, workspaceName, timeout)

        // then
        assertThat(labelSelectorSlot.captured).isEqualTo("controller.devfile.io/devworkspace_name=my-workspace")
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#waitForPodsDeleted throws CancellationException when cancelled via callback`() = runBlocking {
        // given
        val namespace = "test-namespace"
        val workspaceName = "test-workspace"
        val timeout = 10L
        var cancelled = false

        mockkConstructor(CoreV1Api::class)
        val podWithItems = createPodList("pod-1")

        var callCount = 0
        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(namespace)
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } answers {
                    callCount++
                    if (callCount == 2) {
                        cancelled = true
                    }
                    podWithItems
                }
            }
        }

        // when/then
        assertThatThrownBy {
            runBlocking {
                pods.waitForPodsDeleted(namespace, workspaceName, timeout) {
                    if (cancelled) throw CancellationException("Test cancellation")
                }
            }
        }.isInstanceOf(CancellationException::class.java)

        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#waitForPodsDeleted retries when API exception occurs`() = runBlocking {
        // given
        val namespace = "test-namespace"
        val workspaceName = "test-workspace"
        val timeout = 10L

        mockkConstructor(CoreV1Api::class)
        val emptyPodList = createPodList()

        var callCount = 0
        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(namespace)
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } answers {
                    callCount++
                    if (callCount == 1) {
                        throw ApiException("Temporary API error")
                    }
                    emptyPodList
                }
            }
        }

        // when
        val result = pods.waitForPodsDeleted(namespace, workspaceName, timeout)

        // then
        assertThat(result).isTrue()
        assertThat(callCount).isEqualTo(2) // Failed once, then succeeded
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#waitForPodsDeleted continues polling after API errors`() = runBlocking {
        // given
        val namespace = "test-namespace"
        val workspaceName = "test-workspace"
        val timeout = 10L

        mockkConstructor(CoreV1Api::class)
        val podWithItems = createPodList("pod-1")
        val emptyPodList = createPodList()

        var callCount = 0
        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(namespace)
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } answers {
                    callCount++
                    when (callCount) {
                        1 -> podWithItems
                        2 -> throw ApiException("Temporary error")
                        else -> emptyPodList
                    }
                }
            }
        }

        // when
        val result = pods.waitForPodsDeleted(namespace, workspaceName, timeout)

        // then
        assertThat(result).isTrue()
        assertThat(callCount).isGreaterThanOrEqualTo(3) // Had pods, error, then success
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#findFirstRunning returns newest pod when multiple are running and ready`() {
        // given
        mockkConstructor(CoreV1Api::class)
        val oldPod = runningReadyPod("pod-old", "2024-01-01T00:00:00Z")
        val newPod = runningReadyPod("pod-new", "2024-01-02T00:00:00Z")
        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(any())
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } returns V1PodList().apply { items = listOf(oldPod, newPod) }
            }
        }

        // when
        val result = pods.findFirstRunning("ns", "label=foo")

        // then
        assertThat(result?.metadata?.name).isEqualTo("pod-new")
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#findFirstRunning excludes terminating pods`() {
        // given
        mockkConstructor(CoreV1Api::class)
        val terminating = runningReadyPod("terminating", "2024-01-02T00:00:00Z").apply {
            metadata.deletionTimestamp = OffsetDateTime.parse("2024-01-03T00:00:00Z")
        }
        val healthy = runningReadyPod("healthy", "2024-01-01T00:00:00Z")
        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(any())
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } returns V1PodList().apply { items = listOf(terminating, healthy) }
            }
        }

        // when
        val result = pods.findFirstRunning("ns", "label=foo")

        // then
        assertThat(result?.metadata?.name).isEqualTo("healthy")
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#findFirstRunning returns null when no pod is running and ready`() {
        // given
        mockkConstructor(CoreV1Api::class)
        val notReady = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "not-ready"
                creationTimestamp = OffsetDateTime.parse("2024-01-01T00:00:00Z")
            }
            status = V1PodStatus().apply { phase = "Running" }
        }
        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(any())
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } returns V1PodList().apply { items = listOf(notReady) }
            }
        }

        // when
        val result = pods.findFirstRunning("ns", "label=foo")

        // then
        assertThat(result).isNull()
        unmockkConstructor(CoreV1Api::class)
    }
}