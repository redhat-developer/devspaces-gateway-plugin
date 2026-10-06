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
import com.redhat.devtools.gateway.view.steps.workspaces.DevWorkspaceTableController
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1Status
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

    private fun newListener() = mockk<DevWorkspaceListener>(relaxed = true)

    private fun failingRelist(): (String) -> DevWorkspaceRelistResult =
        { error("relist should not be called in this test") }

    private fun newWatcher(
        scope: CoroutineScope,
        relist: (String) -> DevWorkspaceRelistResult = failingRelist(),
        listener: DevWorkspaceListener = newListener(),
        createWatcher: (String, String?) -> Watch<Any>,
    ) = DevWorkspaceWatch(
        namespace = "test-namespace",
        createWatcher = createWatcher,
        relist = relist,
        listener = listener,
        scope = scope,
    )

    @Test
    fun `DevWorkspaceTableController#dispose cancels the shared scope so watch coroutines stop`() {
        // given
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

            val watch = DevWorkspaceTableController(client, tableModel)
            assertThat(watch.scope.isActive).isTrue()

            watch.start(mapOf("test-ns" to "42"))
            awaitCondition { createWatcherCalls.get() >= 1 }
            val ticksBeforeDispose = createWatcherCalls.get()
            // The retry loop re-creates the watcher roughly every 100 ms, but the
            // first re-creation can take longer (mock setup cost), so poll instead
            // of relying on a fixed sleep window.
            awaitCondition(timeoutMs = 10_000) { createWatcherCalls.get() > ticksBeforeDispose }

            // when
            watch.dispose()
            Thread.sleep(200)

            // then
            assertThat(watch.scope.isActive).isFalse()
            val ticksAfterDispose = createWatcherCalls.get()
            Thread.sleep(400)
            assertThat(createWatcherCalls.get()).isEqualTo(ticksAfterDispose)
        } finally {
            unmockkConstructor(DevWorkspaces::class)
        }
    }

    @Test
    fun `DevWorkspaceTableController#stop closes active Watch stream`() = runTest(testScheduler) {
        // given: watcher started, its stream created and active
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val watcher = mockk<Watch<Any>>(relaxed = true)
        every { watcher.iterator() } returns mutableListOf<Watch.Response<Any>>().iterator()
        every { watcher.close() } just runs

        val devWorkspaceWatcher = newWatcher(scope) { _, _ -> watcher }

        devWorkspaceWatcher.start("1")
        testScheduler.advanceTimeBy(50)

        // when
        devWorkspaceWatcher.stop()

        // then: the active stream was closed, which unblocks the watch
        verify(atLeast = 1) { watcher.close() }
    }

    @Test
    fun `403 ApiException stops watch permanently without relisting`() = runTest(testScheduler) {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val createWatcherCalls = AtomicInteger(0)
        val relistCalls = AtomicInteger(0)

        val watch = newWatcher(
            scope,
            createWatcher = { _, _ ->
                createWatcherCalls.incrementAndGet()
                throw ApiException(403, "Forbidden")
            },
            relist = { relistCalls.incrementAndGet(); error("relist must not be called on 403") },
        )

        watch.start("1")
        testScheduler.advanceTimeBy(1_000)

        assertThat(createWatcherCalls.get()).isEqualTo(1)
        assertThat(relistCalls.get()).isEqualTo(0)
    }

    @Test
    fun `410 ApiException triggers relist and resumes from fresh resourceVersion`() = runTest(testScheduler) {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val createWatcherCalls = mutableListOf<String?>()
        val relistCalls = AtomicInteger(0)
        val listener = newListener()

        val fakeDw = DevWorkspace.from(
            mapOf(
                "metadata" to mapOf("name" to "ws1", "namespace" to "test-namespace", "uid" to "uid1"),
                "spec" to mapOf("started" to true),
                "status" to mapOf("phase" to "Running")
            )
        )

        val idleWatcher = mockk<Watch<Any>>(relaxed = true)
        var firstCall = true
        val watch = newWatcher(
            scope,
            createWatcher = { _, rv ->
                createWatcherCalls += rv
                if (firstCall) {
                    firstCall = false
                    throw ApiException(410, "Gone")
                }
                idleWatcher
            },
            relist = { _ ->
                relistCalls.incrementAndGet()
                DevWorkspaceRelistResult(listOf(fakeDw), "200")
            },
            listener = listener,
        )

        watch.start("1")
        // Just enough virtual time for: fail with 410 -> relist -> reconnect once.
        testScheduler.advanceTimeBy(150)
        watch.stop()

        assertThat(relistCalls.get()).isEqualTo(1)
        assertThat(createWatcherCalls.first()).isEqualTo("1")
        assertThat(createWatcherCalls.drop(1)).isNotEmpty().allMatch { it == "200" }
        // Note: onReset is dispatched via Dispatchers.EDT, which needs a running IntelliJ
        // Application and isn't available in this plain-JUnit harness (the same reason no
        // other test in this suite exercises the ADDED/MODIFIED/DELETED dispatch path
        // either) — the dispatch silently fails here and is logged, not asserted. The
        // onReset reconciliation logic itself is covered directly in DevWorkspaceTableUpdaterTest.
    }

    @Test
    fun `in-stream ERROR event with 410 status triggers relist and resumes`() = runTest(testScheduler) {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val createWatcherCalls = mutableListOf<String?>()
        val relistCalls = AtomicInteger(0)
        val listener = newListener()

        val fakeDw = DevWorkspace.from(
            mapOf(
                "metadata" to mapOf("name" to "ws1", "namespace" to "test-namespace", "uid" to "uid1"),
                "spec" to mapOf("started" to true),
                "status" to mapOf("phase" to "Running")
            )
        )

        val errorEvent = Watch.Response<Any>().apply {
            type = "ERROR"
            status = V1Status().code(410).reason("Expired")
        }
        val firstWatcher = mockk<Watch<Any>>(relaxed = true)
        every { firstWatcher.iterator() } returns mutableListOf(errorEvent).iterator()
        val idleWatcher = mockk<Watch<Any>>(relaxed = true)

        var firstCall = true
        val watch = newWatcher(
            scope,
            createWatcher = { _, rv ->
                createWatcherCalls += rv
                if (firstCall) {
                    firstCall = false
                    firstWatcher
                } else {
                    idleWatcher
                }
            },
            relist = { _ ->
                relistCalls.incrementAndGet()
                DevWorkspaceRelistResult(listOf(fakeDw), "200")
            },
            listener = listener,
        )

        watch.start("1")
        // Just enough virtual time for: ERROR/410 event -> relist -> reconnect once.
        testScheduler.advanceTimeBy(150)
        watch.stop()

        assertThat(relistCalls.get()).isEqualTo(1)
        assertThat(createWatcherCalls.first()).isEqualTo("1")
        assertThat(createWatcherCalls.drop(1)).isNotEmpty().allMatch { it == "200" }
        // Note: onReset is dispatched via Dispatchers.EDT, which needs a running IntelliJ
        // Application and isn't available in this plain-JUnit harness (the same reason no
        // other test in this suite exercises the ADDED/MODIFIED/DELETED dispatch path
        // either) — the dispatch silently fails here and is logged, not asserted. The
        // onReset reconciliation logic itself is covered directly in DevWorkspaceTableUpdaterTest.
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
            relist = failingRelist(),
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
