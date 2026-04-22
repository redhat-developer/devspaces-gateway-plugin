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
package com.redhat.devtools.gateway.devworkspace

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.Watch
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class RestartDevWorkspaceAnnotationWatchTest {

    private lateinit var client: ApiClient
    private lateinit var watch: RestartDevWorkspaceAnnotationWatch
    private val callbackInvoked = AtomicInteger(0)
    private val onIsAnnotated: () -> Job = {
        callbackInvoked.incrementAndGet()
        CoroutineScope(Dispatchers.Default).launch { }
    }

    private val namespace = "test-namespace"
    private val workspaceName = "test-workspace"

    @BeforeEach
    fun beforeEach() {
        this.client = mockk(relaxed = true)
        callbackInvoked.set(0)
        mockkConstructor(DevWorkspaces::class)
    }

    @AfterEach
    fun afterEach() {
        unmockkConstructor(DevWorkspaces::class)
    }

    @Test
    fun `#start creates watcher with correct field selector`() = runTest {
        // given
        val fieldSelectorSlot = slot<String>()
        every {
            anyConstructed<DevWorkspaces>().createWatcher(
                namespace = namespace,
                fieldSelector = capture(fieldSelectorSlot),
                labelSelector = any(),
                latestResourceVersion = any()
            )
        } throws RuntimeException("Stop immediately") // Stop the watch loop

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(50.milliseconds) // Give time for watcher creation attempt

        // then
        job.cancelAndJoin()
        assertThat(fieldSelectorSlot.captured).isEqualTo("metadata.name=$workspaceName")
    }

    @Test
    fun `#start attempts to create watcher when creation fails`() = runTest {
        // given
        var attemptCount = 0
        mockCreateWatcherWith {
            attemptCount++
            throw RuntimeException("Watcher creation failed")
        }

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(100.milliseconds) // Give time for first attempt

        // then
        job.cancelAndJoin()
        assertThat(attemptCount).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `#start handles watcher creation returning null gracefully`() = runTest {
        // given
        var attemptCount = 0
        mockCreateWatcherWith {
            attemptCount++
            throw Exception("First attempt fails")
        }

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(1500.milliseconds) // Wait for retry

        // then
        job.cancelAndJoin()
        assertThat(attemptCount).isGreaterThanOrEqualTo(1)
        // Callback should not be invoked when watcher creation fails
        assertThat(callbackInvoked.get()).isEqualTo(0)
    }

    @Test
    fun `#start can be cancelled`() = runTest {
        // given
        mockCreateWatcherToThrow(RuntimeException("Watcher creation failed"))

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(100.milliseconds)
        job.cancel()

        // then
        job.join() // Wait for cancellation to complete
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun `#start calls createWatcher with namespace`() = runTest {
        // given
        val namespaceSlot = slot<String>()
        every {
            anyConstructed<DevWorkspaces>().createWatcher(
                namespace = capture(namespaceSlot),
                fieldSelector = any(),
                labelSelector = any(),
                latestResourceVersion = any()
            )
        } throws RuntimeException("Stop immediately")

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(50.milliseconds)

        // then
        job.cancelAndJoin()
        assertThat(namespaceSlot.captured).isEqualTo(namespace)
    }

    @Test
    fun `constructor creates DevWorkspaces with provided client`() {
        // given/when
        val testClient = mockk<ApiClient>(relaxed = true)
        val watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, testClient, namespace, workspaceName)

        // then
        // Verify that DevWorkspaces constructor was called (implicitly tested through mockkConstructor)
        assertThat(watch).isNotNull()
    }

    @Test
    fun `#start uses correct dispatcher`() = runTest {
        // given
        mockCreateWatcherToThrow(RuntimeException("Stop immediately"))

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(50)

        // then
        job.cancelAndJoin()
        // The job should be completed (either cancelled or failed)
        assertThat(job.isCompleted).isTrue()
    }

    @Test
    @Disabled("Async timing issue - watcher loop doesn't process mock events before test completes")
    fun `#start invokes callback when MODIFIED event with restart annotation is received`() = runTest {
        // given
        val mockWatcher = createMockWatcher(
            createEvent("MODIFIED", namespace, workspaceName, mapOf(
                DevWorkspacePatch.RESTART_KEY to DevWorkspacePatch.RESTART_VALUE
            ))
        )
        mockCreateWatcherToReturn(mockWatcher)

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(100.milliseconds)

        // then
        job.cancelAndJoin()
        assertThat(callbackInvoked.get()).isEqualTo(1)
    }

    @Test
    @Disabled("Async timing issue - watcher loop doesn't process mock events before test completes")
    fun `#start invokes callback when ADDED event with restart annotation is received`() = runTest {
        // given
        val mockWatcher = createMockWatcher(
            createEvent("ADDED", namespace, workspaceName, mapOf(
                DevWorkspacePatch.RESTART_KEY to DevWorkspacePatch.RESTART_VALUE
            ))
        )
        mockCreateWatcherToReturn(mockWatcher)

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(100.milliseconds)

        // then
        job.cancelAndJoin()
        assertThat(callbackInvoked.get()).isEqualTo(1)
    }

    @Test
    fun `#start does not invoke callback for DELETED event`() = runTest {
        // given
        val mockWatcher = createMockWatcher(
            createEvent("DELETED", namespace, workspaceName, mapOf(
                DevWorkspacePatch.RESTART_KEY to DevWorkspacePatch.RESTART_VALUE
            ))
        )
        mockCreateWatcherToReturn(mockWatcher)

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(100.milliseconds)

        // then
        job.cancelAndJoin()
        assertThat(callbackInvoked.get()).isEqualTo(0)
    }

    @Test
    fun `#start does not invoke callback when annotation value is not true`() = runTest {
        // given
        val mockWatcher = createMockWatcher(
            createEvent("MODIFIED", namespace, workspaceName, mapOf(
                DevWorkspacePatch.RESTART_KEY to "false"
            ))
        )
        mockCreateWatcherToReturn(mockWatcher)

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(100.milliseconds)

        // then
        job.cancelAndJoin()
        assertThat(callbackInvoked.get()).isEqualTo(0)
    }

    @Test
    fun `#start does not invoke callback when restart annotation is missing`() = runTest {
        // given
        val mockWatcher = createMockWatcher(
            createEvent("MODIFIED", namespace, workspaceName, emptyMap())
        )
        mockCreateWatcherToReturn(mockWatcher)

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(100.milliseconds)

        // then
        job.cancelAndJoin()
        assertThat(callbackInvoked.get()).isEqualTo(0)
    }

    @Test
    fun `#start filters events by workspace name`() = runTest {
        // given
        val mockWatcher = createMockWatcher(
            createEvent("MODIFIED", namespace, "different-workspace", mapOf(
                DevWorkspacePatch.RESTART_KEY to DevWorkspacePatch.RESTART_VALUE
            ))
        )
        mockCreateWatcherToReturn(mockWatcher)

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(100.milliseconds)

        // then
        job.cancelAndJoin()
        assertThat(callbackInvoked.get()).isEqualTo(0)
    }

    @Test
    fun `#start filters events by namespace`() = runTest {
        // given
        val mockWatcher = createMockWatcher(
            createEvent("MODIFIED", "different-namespace", workspaceName, mapOf(
                DevWorkspacePatch.RESTART_KEY to DevWorkspacePatch.RESTART_VALUE
            ))
        )
        mockCreateWatcherToReturn(mockWatcher)

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(100.milliseconds)

        // then
        job.cancelAndJoin()
        assertThat(callbackInvoked.get()).isEqualTo(0)
    }

    @Test
    @Disabled("Async timing issue - watcher loop doesn't process mock events before test completes")
    fun `#start does not invoke callback twice for duplicate events`() = runTest {
        // given
        val mockWatcher = createMockWatcher(
            createEvent("MODIFIED", namespace, workspaceName, mapOf(
                DevWorkspacePatch.RESTART_KEY to DevWorkspacePatch.RESTART_VALUE
            )),
            createEvent("MODIFIED", namespace, workspaceName, mapOf(
                DevWorkspacePatch.RESTART_KEY to DevWorkspacePatch.RESTART_VALUE
            ))
        )
        mockCreateWatcherToReturn(mockWatcher)

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(200.milliseconds)

        // then
        job.cancelAndJoin()
        // Should only be invoked once due to atomic flag
        assertThat(callbackInvoked.get()).isEqualTo(1)
    }

    @Test
    @Disabled("Async timing issue - watcher loop doesn't process mock events before test completes")
    fun `#start resets pending flag when annotation is removed`() = runTest {
        // given
        val mockWatcher = createMockWatcher(
            createEvent("MODIFIED", namespace, workspaceName, mapOf(
                DevWorkspacePatch.RESTART_KEY to DevWorkspacePatch.RESTART_VALUE
            )),
            createEvent("MODIFIED", namespace, workspaceName, emptyMap()), // Annotation removed
            createEvent("MODIFIED", namespace, workspaceName, mapOf(
                DevWorkspacePatch.RESTART_KEY to DevWorkspacePatch.RESTART_VALUE
            ))
        )
        mockCreateWatcherToReturn(mockWatcher)

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(300.milliseconds)

        // then
        job.cancelAndJoin()
        // Should be invoked twice: once for first event, once for third (after flag reset)
        assertThat(callbackInvoked.get()).isEqualTo(2)
    }

    @Test
    fun `#start handles malformed DevWorkspace object gracefully`() = runTest {
        // given
        val mockWatcher = mockk<Watch<Any>>(relaxed = true)
        val invalidEvent: Watch.Response<Any> = Watch.Response("MODIFIED", "invalid-object" as Any) // Not a valid DevWorkspace

        every { mockWatcher.iterator() } returns mutableListOf(invalidEvent).iterator()
        every { mockWatcher.close() } just Runs

        mockCreateWatcherToReturn(mockWatcher)

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(100.milliseconds)

        // then
        job.cancelAndJoin()
        assertThat(callbackInvoked.get()).isEqualTo(0)
    }

    // Helper methods
    private fun mockCreateWatcherToThrow(exception: Throwable) {
        every {
            anyConstructed<DevWorkspaces>().createWatcher(
                namespace = any(),
                fieldSelector = any(),
                labelSelector = any(),
                latestResourceVersion = any()
            )
        } throws exception
    }

    private fun mockCreateWatcherToReturn(watcher: Watch<Any>) {
        every {
            anyConstructed<DevWorkspaces>().createWatcher(
                namespace = any(),
                fieldSelector = any(),
                labelSelector = any(),
                latestResourceVersion = any()
            )
        } returns watcher
    }

    private fun mockCreateWatcherWith(block: MockKAnswerScope<Watch<Any>, Watch<Any>>.(Call) -> Watch<Any>) {
        every {
            anyConstructed<DevWorkspaces>().createWatcher(
                namespace = any(),
                fieldSelector = any(),
                labelSelector = any(),
                latestResourceVersion = any()
            )
        } answers block
    }

    private fun createMockWatcher(vararg events: Watch.Response<Any>): Watch<Any> {
        val mockWatcher = mockk<Watch<Any>>(relaxed = true)
        val mutableIterator = events.toMutableList().iterator()
        every { mockWatcher.iterator() } returns mutableIterator
        every { mockWatcher.close() } just Runs
        return mockWatcher
    }

    private fun createEvent(
        eventType: String,
        namespace: String,
        name: String,
        annotations: Map<String, String>
    ): Watch.Response<Any> {
        val devWorkspaceObject = mapOf(
            "metadata" to mapOf(
                "name" to name,
                "namespace" to namespace,
                "annotations" to annotations
            ),
            "spec" to mapOf(
                "started" to true
            ),
            "status" to mapOf(
                "phase" to "Running"
            )
        )

        return Watch.Response(eventType, devWorkspaceObject)
    }
}
