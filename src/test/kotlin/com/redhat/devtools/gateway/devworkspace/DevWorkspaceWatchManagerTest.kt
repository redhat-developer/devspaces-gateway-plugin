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
package com.redhat.devtools.gateway.devworkspace

import com.redhat.devtools.gateway.view.steps.workspaces.DevWorkspaceTableModel
import com.redhat.devtools.gateway.view.steps.workspaces.WorkspacesWatch
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.Watch
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class DevWorkspaceWatchManagerTest {

    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(testScheduler)

    private val createFilter: (String) -> ((DevWorkspace) -> Boolean) = { { true } }

    private fun newListener() = mockk<DevWorkspaceListener>(relaxed = true)

    private fun newWatcher(
        scope: CoroutineScope,
        createWatcher: (String, String?) -> Watch<Any>,
    ) = DevWorkspaceWatch(
        namespace = "test-namespace",
        createWatcher = createWatcher,
        createFilter = createFilter,
        listener = newListener(),
        scope = scope,
    )

    @Test
    fun `dispose cancels the shared scope so watch coroutines stop`() {
        val client = mockk<ApiClient>(relaxed = true)
        val tableModel = mockk<DevWorkspaceTableModel>(relaxed = true)
        val createWatcherCalls = AtomicInteger(0)

        mockkConstructor(DevWorkspaces::class)
        try {
            every {
                anyConstructed<DevWorkspaces>().createWatcher(any(), any(), any(), any())
            } answers {
                createWatcherCalls.incrementAndGet()
                mockk<Watch<Any>>(relaxed = true)
            }

            val watch = WorkspacesWatch(client, tableModel)
            assertThat(watch.scope.isActive).isTrue()

            watch.start(mapOf("test-ns" to "42"))
            awaitCondition { createWatcherCalls.get() >= 1 }
            val ticksBeforeDispose = createWatcherCalls.get()
            // The retry loop re-creates the watcher roughly every 100 ms, but the
            // first re-creation can take longer (mock setup cost), so poll instead
            // of relying on a fixed sleep window.
            awaitCondition(timeoutMs = 10_000) { createWatcherCalls.get() > ticksBeforeDispose }

            watch.dispose()
            Thread.sleep(200)

            assertThat(watch.scope.isActive).isFalse()
            val ticksAfterDispose = createWatcherCalls.get()
            Thread.sleep(400)
            assertThat(createWatcherCalls.get()).isEqualTo(ticksAfterDispose)
        } finally {
            unmockkConstructor(DevWorkspaces::class)
        }
    }

    @Test
    fun `stop closes active Watch stream`() = runTest(testScheduler) {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val watcher = mockk<Watch<Any>>(relaxed = true)
        every { watcher.iterator() } returns mutableListOf<Watch.Response<Any>>().iterator()
        every { watcher.close() } just runs

        val devWorkspaceWatcher = newWatcher(scope) { _, _ -> watcher }

        // given: watcher started, its stream created and active
        devWorkspaceWatcher.start("1")
        testScheduler.advanceTimeBy(50)

        // when
        devWorkspaceWatcher.stop()

        // then: the active stream was closed, which unblocks the watch
        verify(atLeast = 1) { watcher.close() }
    }

    @Test
    fun `double start on DevWorkspaceWatchManager does not duplicate watchers`() = runTest(testScheduler) {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        var createWatcherCalls = 0
        val manager = DevWorkspaceWatchManager(
            createWatch = { _, _ ->
                createWatcherCalls++
                mockk<Watch<Any>>(relaxed = true)
            },
            createFilter = createFilter,
            listener = newListener(),
            scope = scope,
        )

        val namespaces = mapOf("ns1" to "1", "ns2" to "2")

        // when: start twice; the second start must replace, not accumulate
        manager.start(namespaces)
        testScheduler.advanceTimeBy(50)
        manager.start(namespaces)
        testScheduler.advanceTimeBy(50)
        manager.stop()

        // then: 2 namespaces x 2 starts, never a duplicate 8
        assertThat(createWatcherCalls).isEqualTo(4)
    }

    @Test
    fun `MODIFIED event ignored when filter does not match`() = runTest(testScheduler) {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val listener = newListener()
        val eventObject = mapOf(
            "metadata" to mapOf(
                "name" to "test-workspace",
                "namespace" to "test-namespace",
                "uid" to "test-uid",
                "annotations" to emptyMap<String, String>(),
                "labels" to emptyMap<String, String>()
            ),
            "spec" to mapOf("started" to true),
            "status" to mapOf("phase" to "Running")
        )
        val response = Watch.Response<Any>("MODIFIED", eventObject)
        val watcher = mockk<Watch<Any>>(relaxed = true)
        every { watcher.iterator() } returns mutableListOf(response).iterator()

        val nonMatchingWatcher = DevWorkspaceWatch(
            namespace = "test-namespace",
            createWatcher = { _, _ -> watcher },
            createFilter = { { false } },
            listener = listener,
            scope = scope,
        )

        nonMatchingWatcher.start("1")
        testScheduler.advanceTimeBy(500)
        nonMatchingWatcher.stop()

        verify(exactly = 0) { listener.onUpdated(any()) }
        verify(exactly = 0) { listener.onDeleted(any()) }
    }

    @Test
    fun `stop during blocking watch unblocks quickly`() = runTest(testScheduler) {
        // A real dispatcher is required: the blocking iterator must run on a
        // separate thread so that stop() can be invoked from the test.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val unblock = CountDownLatch(1)
        val enteredHasNext = AtomicBoolean(false)
        val unblocked = AtomicBoolean(false)

        val watcher = mockk<Watch<Any>>()
        every { watcher.iterator() } returns object : MutableIterator<Watch.Response<Any>> {
            override fun hasNext(): Boolean {
                enteredHasNext.set(true)
                unblock.await(30, TimeUnit.SECONDS)
                unblocked.set(true)
                return false
            }

            override fun next(): Watch.Response<Any> = throw NoSuchElementException()
            override fun remove() = Unit
        }
        every { watcher.close() } answers { unblock.countDown() }

        val devWorkspaceWatcher = newWatcher(scope) { _, _ -> watcher }
        devWorkspaceWatcher.start("1")

        val deadline = System.currentTimeMillis() + 5_000
        while (!enteredHasNext.get()) {
            check(System.currentTimeMillis() < deadline) { "watch loop never entered hasNext()" }
            Thread.sleep(10)
        }

        // when
        val start = System.nanoTime()
        devWorkspaceWatcher.stop()
        val elapsedNanos = System.nanoTime() - start

        // then: close() unblocked the blocked iteration without a long delay
        assertThat(elapsedNanos).isLessThan(1_000_000_000L)

        val unblockDeadline = System.currentTimeMillis() + 5_000
        while (!unblocked.get()) {
            check(System.currentTimeMillis() < unblockDeadline) { "watch loop never unblocked after close()" }
            Thread.sleep(10)
        }
        Thread.sleep(100)
    }

    private fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) {
                "Timed out after ${timeoutMs} ms waiting for condition"
            }
            Thread.sleep(20)
        }
    }
}
