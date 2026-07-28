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

import com.redhat.devtools.gateway.openshift.apiclient.ApiClientUtils
import io.kubernetes.client.PortForward
import io.kubernetes.client.custom.IOTrio
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ListMeta
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.openapi.models.V1PodStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
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
        val closeable = pods.forward(pod, localPort, remotePort)

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
        } throws mockk<IOException>(relaxed = true)

        // when
        val closeable = pods.forward(pod, localPort, remotePort)

        // then
        // wait for the server to start
        runBlocking { delay(100.milliseconds) }
        Socket("127.0.0.1", localPort).apply {
            close() // trigger retry
        }
        runBlocking { delay(6000.milliseconds) } // 5 attempts * 1 second

        closeable.use { closeable ->
            verify(atLeast = 2) { // 2+ retries
                anyConstructed<PortForward>().forward(pod, listOf(remotePort))
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
    fun `#isPodRunning returns true when pod phase is Running`() {
        // given
        val runningPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "running-pod"
                namespace = "test-ns"
            }
        }

        mockkConstructor(CoreV1Api::class)
        val response = mockk<V1Pod>(relaxed = true)
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("running-pod", "test-ns")
        } returns mockk { every { execute() } returns response }
        every { response.status } returns V1PodStatus().apply { phase = "Running" }

        // when
        val result = pods.isPodRunning(runningPod)

        // then
        assertThat(result).isTrue()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#isPodRunning returns false when pod phase is Pending`() {
        // given
        val pendingPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "pending-pod"
                namespace = "test-ns"
            }
        }

        mockkConstructor(CoreV1Api::class)
        val response = mockk<V1Pod>(relaxed = true)
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("pending-pod", "test-ns")
        } returns mockk { every { execute() } returns response }
        every { response.status } returns V1PodStatus().apply { phase = "Pending" }

        // when
        val result = pods.isPodRunning(pendingPod)

        // then
        assertThat(result).isFalse()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#isPodRunning returns false when pod phase is Failed`() {
        // given
        val failedPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "failed-pod"
                namespace = "test-ns"
            }
        }

        mockkConstructor(CoreV1Api::class)
        val response = mockk<V1Pod>(relaxed = true)
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("failed-pod", "test-ns")
        } returns mockk { every { execute() } returns response }
        every { response.status } returns V1PodStatus().apply { phase = "Failed" }

        // when
        val result = pods.isPodRunning(failedPod)

        // then
        assertThat(result).isFalse()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#isPodRunning returns false when pod phase is Succeeded`() {
        // given
        val succeededPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "succeeded-pod"
                namespace = "test-ns"
            }
        }

        mockkConstructor(CoreV1Api::class)
        val response = mockk<V1Pod>(relaxed = true)
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("succeeded-pod", "test-ns")
        } returns mockk { every { execute() } returns response }
        every { response.status } returns V1PodStatus().apply { phase = "Succeeded" }

        // when
        val result = pods.isPodRunning(succeededPod)

        // then
        assertThat(result).isFalse()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#isPodRunning returns false when pod has no name`() {
        // given — pod with null name
        val noNamePod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                namespace = "test-ns"
            }
        }

        // when
        val result = pods.isPodRunning(noNamePod)

        // then — no API call needed
        assertThat(result).isFalse()
    }

    @Test
    fun `#isPodRunning returns false when pod has no namespace`() {
        // given — pod with null namespace
        val noNsPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "my-pod"
            }
        }

        // when
        val result = pods.isPodRunning(noNsPod)

        // then — no API call needed
        assertThat(result).isFalse()
    }

    @Test
    fun `#isPodRunning returns false when pod has no metadata`() {
        // given
        val noMetaPod = V1Pod()

        // when
        val result = pods.isPodRunning(noMetaPod)

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `#isPodRunning returns false when API call throws exception`() {
        // given
        val pod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "error-pod"
                namespace = "test-ns"
            }
        }

        mockkConstructor(CoreV1Api::class)
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("error-pod", "test-ns")
        } returns mockk { every { execute() } throws ApiException("connection refused") }

        // when
        val result = pods.isPodRunning(pod)

        // then
        assertThat(result).isFalse()
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
    fun `#exec cancels cleanly when checkCancelled throws`() = runBlocking {
        // given — ContainerAwareExec returns a fake process that produces data
        val stdout = PipedOutputStream()
        val stdin = PipedInputStream(stdout)
        val fakeProcess = mockk<Process>(relaxed = true)
        every { fakeProcess.inputStream } returns stdin
        every { fakeProcess.errorStream } returns ByteArrayInputStream(ByteArray(0))
        every { fakeProcess.outputStream } returns mockk(relaxed = true)
        every { fakeProcess.isAlive } returns true

        mockkConstructor(ContainerAwareExec::class)
        val fakeHandle = ContainerAwareExec.ExecHandle(
            future = java.util.concurrent.CompletableFuture.completedFuture(0),
            job = mockk(relaxed = true)
        )
        every {
            anyConstructed<ContainerAwareExec>().containerAwareExec(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } answers {
            @Suppress("UNCHECKED_CAST")
            val onOpen = it.invocation.args[4] as java.util.function.Consumer<IOTrio>
            val io = IOTrio()
            io.stdout = fakeProcess.inputStream
            io.stderr = fakeProcess.errorStream
            io.stdin = fakeProcess.outputStream
            onOpen.accept(io)
            fakeHandle
        }

        mockkObject(ApiClientUtils)
        val execClient = mockk<ApiClient>(relaxed = true)
        every { ApiClientUtils.cloneForExec(any()) } returns execClient

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val testPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "test-pod"
                namespace = "test-ns"
            }
        }

        // when — launch exec, then cancel it
        val job = scope.launch {
            pods.exec(
                pod = testPod,
                command = arrayOf("echo"),
                container = "test-container",
                timeout = 60,
                checkCancelled = { throw CancellationException("user cancelled") }
            )
        }
        @Suppress("ConvertLongToDuration")
        delay(100) // let exec start
        job.cancel()
        job.join()

        // then — should complete without hanging
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun `#exec propagates error from process`() = runBlocking {
        // given — ContainerAwareExec throws on exec
        mockkConstructor(ContainerAwareExec::class)
        every {
            anyConstructed<ContainerAwareExec>().containerAwareExec(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } throws IOException("connection refused")

        mockkObject(ApiClientUtils)
        val execClient = mockk<ApiClient>(relaxed = true)
        every { ApiClientUtils.cloneForExec(any()) } returns execClient

        val testPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "test-pod"
                namespace = "test-ns"
            }
        }

        // when / then
        assertThatThrownBy {
            runBlocking {
                pods.exec(
                    pod = testPod,
                    command = arrayOf("echo"),
                    container = "test-container"
                )
            }
        }.isInstanceOf(IOException::class.java)

        // then — exec client should be shut down
        verify {
            execClient.httpClient.dispatcher.executorService.shutdownNow()
            execClient.httpClient.connectionPool.evictAll()
        }
    }

    @Test
    fun `#exec shuts down exec client on cancellation`() = runBlocking {
        // given — ContainerAwareExec returns a blocking fake process
        val fakeProcess = mockk<Process>(relaxed = true)
        every { fakeProcess.inputStream } returns PipedInputStream()
        every { fakeProcess.errorStream } returns ByteArrayInputStream(ByteArray(0))
        every { fakeProcess.outputStream } returns mockk(relaxed = true)
        every { fakeProcess.isAlive } returns true

        mockkConstructor(ContainerAwareExec::class)
        val fakeHandle = ContainerAwareExec.ExecHandle(
            future = java.util.concurrent.CompletableFuture.completedFuture(0),
            job = mockk(relaxed = true)
        )
        every {
            anyConstructed<ContainerAwareExec>().containerAwareExec(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } answers {
            @Suppress("UNCHECKED_CAST")
            val onOpen = it.invocation.args[4] as java.util.function.Consumer<IOTrio>
            val io = IOTrio()
            io.stdout = fakeProcess.inputStream
            io.stderr = fakeProcess.errorStream
            io.stdin = fakeProcess.outputStream
            onOpen.accept(io)
            fakeHandle
        }

        mockkObject(ApiClientUtils)
        val execClient = mockk<ApiClient>(relaxed = true)
        every { ApiClientUtils.cloneForExec(any()) } returns execClient

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val testPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "test-pod"
                namespace = "test-ns"
            }
        }

        // when — launch and cancel
        val job = scope.launch {
            pods.exec(
                pod = testPod,
                command = arrayOf("echo"),
                container = "test-container",
                timeout = 60
            )
        }
        @Suppress("ConvertLongToDuration")
        delay(100)
        job.cancel()
        job.join()

        // then — exec client dispatcher and connection pool should be shut down
        verify {
            execClient.httpClient.dispatcher.executorService.shutdownNow()
            execClient.httpClient.connectionPool.evictAll()
        }
    }
}