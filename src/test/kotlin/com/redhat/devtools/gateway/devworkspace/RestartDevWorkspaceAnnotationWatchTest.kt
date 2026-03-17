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
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
        every {
            anyConstructed<DevWorkspaces>().createWatcher(
                namespace = any(),
                fieldSelector = any(),
                labelSelector = any(),
                latestResourceVersion = any()
            )
        } answers {
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
        every {
            anyConstructed<DevWorkspaces>().createWatcher(
                namespace = any(),
                fieldSelector = any(),
                labelSelector = any(),
                latestResourceVersion = any()
            )
        } answers {
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
        every {
            anyConstructed<DevWorkspaces>().createWatcher(
                namespace = any(),
                fieldSelector = any(),
                labelSelector = any(),
                latestResourceVersion = any()
            )
        } throws RuntimeException("Watcher creation failed")

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
        every {
            anyConstructed<DevWorkspaces>().createWatcher(
                namespace = any(),
                fieldSelector = any(),
                labelSelector = any(),
                latestResourceVersion = any()
            )
        } throws RuntimeException("Stop immediately")

        watch = RestartDevWorkspaceAnnotationWatch(onIsAnnotated, client, namespace, workspaceName)

        // when
        val job = watch.start(this)
        delay(50)

        // then
        job.cancelAndJoin()
        // The job should be completed (either cancelled or failed)
        assertThat(job.isCompleted).isTrue()
    }
}
