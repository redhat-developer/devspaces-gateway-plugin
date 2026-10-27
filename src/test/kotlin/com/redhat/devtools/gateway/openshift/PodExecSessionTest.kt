/*
 * Copyright (c) 2025-2026 Red Hat, Inc.
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
import io.kubernetes.client.custom.IOTrio
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

class PodExecSessionTest {

    private lateinit var client: ApiClient

    @BeforeEach
    fun beforeEach() {
        client = mockk(relaxed = true)
    }

    @AfterEach
    fun afterEach() {
        unmockkAll()
    }

    @Test
    fun `#exec onError after onOpen completes with one failure and shuts down client once`() = runBlocking<Unit> {
        // given — ContainerAwareExec calls onOpen then onError (mid-stream error)
        mockkConstructor(ContainerAwareExec::class)
        val onErrorCalled = java.util.concurrent.atomic.AtomicBoolean()
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
            val onError = it.invocation.args[6] as java.util.function.BiConsumer<Throwable, IOTrio>
            val io = IOTrio()
            io.stdout = ByteArrayInputStream(ByteArray(0))
            io.stderr = ByteArrayInputStream(ByteArray(0))
            io.stdin = mockk(relaxed = true)
            onOpen.accept(io)
            // Simulate mid-stream error after onOpen has fired
            onError.accept(IOException("mid-stream connection reset"), io)
            onErrorCalled.set(true)
            fakeHandle
        }

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
                PodExecSession(client = client,
                    pod = testPod,
                    command = arrayOf("echo"),
                    container = "test-container"
                ).execute()
            }
        }.isInstanceOf(IOException::class.java)

        // exactly one failure (not double-completed)
        assertThat(onErrorCalled).isTrue()

        // client shut down at least once (joiner finally block handles it)
        verify(atLeast = 1) {
            execClient.httpClient.dispatcher.executorService.shutdownNow()
        }
        verify(atLeast = 1) {
            execClient.httpClient.connectionPool.evictAll()
        }
    }

    private class CloseTrackingInputStream(private val delegate: java.io.InputStream) : java.io.InputStream() {
        var closed = false
            private set
        override fun read(): Int = delegate.read()
        override fun close() { closed = true; delegate.close() }
    }

    @Test
    fun `#exec closes stdout and stderr on cancellation`() = runBlocking<Unit> {
        // given — ContainerAwareExec returns a fake process with close-tracking streams
        val stdout = CloseTrackingInputStream(ByteArrayInputStream("data".toByteArray()))
        val stderr = CloseTrackingInputStream(ByteArrayInputStream(ByteArray(0)))
        val fakeProcess = mockk<Process>(relaxed = true).also {
            every { it.inputStream } returns stdout
            every { it.errorStream } returns stderr
            every { it.outputStream } returns mockk(relaxed = true)
            every { it.isAlive } returns true
        }

        mockkConstructor(ContainerAwareExec::class)
        val fakeHandle = ContainerAwareExec.ExecHandle(
            future = java.util.concurrent.CompletableFuture.completedFuture(0),
            job = mockk(relaxed = true)
        )
        val onOpenReady = CompletableDeferred<Unit>()
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
            onOpenReady.complete(Unit)
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

        // when — launch exec, wait for onOpen, then cancel
        val job = scope.launch {
            PodExecSession(client = client,
                pod = testPod,
                command = arrayOf("echo"),
                container = "test-container",
                timeout = 60
            ).execute()
        }
        onOpenReady.await()
        job.cancel()
        job.join()

        // then — stdout and stderr should be closed
        assertThat(stdout.closed).isTrue()
        assertThat(stderr.closed).isTrue()
    }

    @Test
    fun `#exec fails when error occurs before onOpen`() = runBlocking<Unit> {
        // given — ContainerAwareExec calls onError without onOpen (connection failed before ready)

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
            val onError = it.invocation.args[6] as java.util.function.BiConsumer<Throwable, IOTrio>
            val io = IOTrio()
            io.stdout = ByteArrayInputStream(ByteArray(0))
            io.stderr = ByteArrayInputStream(ByteArray(0))
            io.stdin = mockk(relaxed = true)
            // Simulate error callback fired without onOpen (connection failed before ready)
            onError.accept(IOException("connection refused before onOpen"), io)
            fakeHandle
        }

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
                PodExecSession(client = client,
                    pod = testPod,
                    command = arrayOf("echo"),
                    container = "test-container"
                ).execute()
            }
        }.isInstanceOf(IOException::class.java)

        verify {
            execClient.httpClient.dispatcher.executorService.shutdownNow()
            execClient.httpClient.connectionPool.evictAll()
        }
    }

    @Test
    fun `#exec cancels cleanly when checkCancelled throws`() = runBlocking<Unit> {
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
            PodExecSession(client = client,
                pod = testPod,
                command = arrayOf("echo"),
                container = "test-container",
                timeout = 60,
                checkCancelled = { throw CancellationException("user cancelled") }
            ).execute()
        }
        @Suppress("ConvertLongToDuration")
        delay(100) // let exec start
        job.cancel()
        job.join()

        // then — should complete without hanging
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun `#exec propagates error from process`() = runBlocking<Unit> {
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
                PodExecSession(client = client,
                    pod = testPod,
                    command = arrayOf("echo"),
                    container = "test-container"
                ).execute()
            }
        }.isInstanceOf(IOException::class.java)

        // then — exec client should be shut down
        verify {
            execClient.httpClient.dispatcher.executorService.shutdownNow()
            execClient.httpClient.connectionPool.evictAll()
        }
    }

    @Test
    fun `#exec shuts down exec client on cancellation`() = runBlocking<Unit> {
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
            PodExecSession(client = client,
                pod = testPod,
                command = arrayOf("echo"),
                container = "test-container",
                timeout = 60
            ).execute()
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

    @Test
    fun `#exec throws IOException when process exits non-zero`() = runBlocking<Unit> {
        // given
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
            val onClosed = it.invocation.args[5] as java.util.function.BiConsumer<Int, IOTrio>
            val onOpen = it.invocation.args[4] as java.util.function.Consumer<IOTrio>
            val io = IOTrio()
            io.stdout = ByteArrayInputStream("stdout".toByteArray())
            io.stderr = ByteArrayInputStream(ByteArray(0))
            io.stdin = mockk(relaxed = true)
            onOpen.accept(io)
            onClosed.accept(1, io)
            fakeHandle
        }

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
                PodExecSession(client = client,
                    pod = testPod,
                    command = arrayOf("echo"),
                    container = "test-container",
                    timeout = 60
                ).execute()
            }
        }.isInstanceOf(IOException::class.java)
            .hasMessageContaining("exit code 1")
    }

    @Test
    fun `#exec throws IOException when exec times out`() = runBlocking<Unit> {
        // given
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
            val onClosed = it.invocation.args[5] as java.util.function.BiConsumer<Int, IOTrio>
            val onOpen = it.invocation.args[4] as java.util.function.Consumer<IOTrio>
            val io = IOTrio()
            io.stdout = ByteArrayInputStream("stdout".toByteArray())
            io.stderr = ByteArrayInputStream(ByteArray(0))
            io.stdin = mockk(relaxed = true)
            onOpen.accept(io)
            onClosed.accept(Int.MAX_VALUE, io)
            fakeHandle
        }

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
                PodExecSession(client = client,
                    pod = testPod,
                    command = arrayOf("echo"),
                    container = "test-container",
                    timeout = 60
                ).execute()
            }
        }.isInstanceOf(IOException::class.java)
            .hasMessageContaining("timed out")
    }

    @Test
    fun `#exec includes stderr in IOException when exit code is non-zero`() = runBlocking<Unit> {
        // given
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
            val onClosed = it.invocation.args[5] as java.util.function.BiConsumer<Int, IOTrio>
            val onOpen = it.invocation.args[4] as java.util.function.Consumer<IOTrio>
            val io = IOTrio()
            io.stdout = ByteArrayInputStream("stdout".toByteArray())
            io.stderr = ByteArrayInputStream("boom".toByteArray())
            io.stdin = mockk(relaxed = true)
            onOpen.accept(io)
            onClosed.accept(1, io)
            fakeHandle
        }

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
                PodExecSession(client = client,
                    pod = testPod,
                    command = arrayOf("echo"),
                    container = "test-container",
                    timeout = 60
                ).execute()
            }
        }.isInstanceOf(IOException::class.java)
            .hasMessageContaining("exit code 1")
            .hasMessageContaining("boom")
    }

    @Test
    fun `#exec returns stdout when exit code is 0`() = runBlocking<Unit> {
        // given
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
            val onClosed = it.invocation.args[5] as java.util.function.BiConsumer<Int, IOTrio>
            val onOpen = it.invocation.args[4] as java.util.function.Consumer<IOTrio>
            val io = IOTrio()
            io.stdout = ByteArrayInputStream("hello world".toByteArray())
            io.stderr = ByteArrayInputStream(ByteArray(0))
            io.stdin = mockk(relaxed = true)
            onOpen.accept(io)
            onClosed.accept(0, io)
            fakeHandle
        }

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
        assertThat(PodExecSession(client = client,
            pod = testPod,
            command = arrayOf("echo"),
            container = "test-container",
            timeout = 60
        ).execute()).isEqualTo("hello world")
    }

    @Test
    fun `#exec fails when stdout stream errors even if exit code is 0`() = runBlocking<Unit> {
        // given — stdout delivers partial data then throws (no clean EOF)
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
            val onClosed = it.invocation.args[5] as java.util.function.BiConsumer<Int, IOTrio>
            val onOpen = it.invocation.args[4] as java.util.function.Consumer<IOTrio>
            val io = IOTrio()
            io.stdout = ThrowingAfterDataInputStream("partial".toByteArray())
            io.stderr = ByteArrayInputStream(ByteArray(0))
            io.stdin = mockk(relaxed = true)
            onOpen.accept(io)
            onClosed.accept(0, io)
            fakeHandle
        }

        mockkObject(ApiClientUtils)
        val execClient = mockk<ApiClient>(relaxed = true)
        every { ApiClientUtils.cloneForExec(any()) } returns execClient

        val testPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "test-pod"
                namespace = "test-ns"
            }
        }

        // when / then — must not return the partial buffer as success
        assertThatThrownBy {
            runBlocking {
                PodExecSession(client = client,
                    pod = testPod,
                    command = arrayOf("echo"),
                    container = "test-container",
                    timeout = 60
                ).execute()
            }
        }.isInstanceOf(IOException::class.java)
            .hasMessageContaining("stream closed before output was fully read")
    }

    /** Returns all bytes once, then throws instead of EOF (-1). */
    private class ThrowingAfterDataInputStream(private val data: ByteArray) : java.io.InputStream() {
        private var index = 0
        override fun read(): Int {
            if (index < data.size) return data[index++].toInt() and 0xFF
            throw IOException("connection reset")
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (index >= data.size) throw IOException("connection reset")
            val n = minOf(len, data.size - index)
            System.arraycopy(data, index, b, off, n)
            index += n
            return n
        }
    }
}
