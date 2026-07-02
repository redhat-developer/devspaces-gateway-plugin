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
package com.redhat.devtools.gateway

import com.redhat.devtools.gateway.server.RemoteIDEServer
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class WorkspacePodTrackerTest {

    private lateinit var remoteIdeServer: RemoteIDEServer
    private lateinit var tracker: WorkspacePodTracker

    @BeforeEach
    fun beforeEach() {
        remoteIdeServer = mockk()
        tracker = WorkspacePodTracker(remoteIdeServer)
    }

    @Test
    fun `#seed and first resolvePod with same UID does not fire onPodRoll`() {
        runBlocking {
            val pod = podWithUid("pod-a", "uid-1")
            tracker.seed(pod)
            coEvery { remoteIdeServer.refreshPod() } returns pod

            val rollCount = AtomicInteger(0)
            tracker.onPodRoll = { rollCount.incrementAndGet() }

            val result = tracker.resolvePod()

            assertThat(result).isInstanceOf(PodResolution.Ready::class.java)
            assertThat(rollCount.get()).isZero()
        }
    }

    @Test
    fun `#resolvePod fires onPodRoll when UID changes`() {
        runBlocking {
            val podA = podWithUid("pod-a", "uid-1")
            val podB = podWithUid("pod-b", "uid-2")
            tracker.seed(podA)
            coEvery { remoteIdeServer.refreshPod() } returns podB

            var rolledPod: V1Pod? = null
            tracker.onPodRoll = { rolledPod = it }

            val result = tracker.resolvePod()

            assertThat(rolledPod).isSameAs(podB)
            assertThat(result).isEqualTo(PodResolution.RollDelegated(podB))
        }
    }

    @Test
    fun `#resolvePod returns Unavailable and does not fire onPodRoll when refreshPod throws`() {
        runBlocking {
            tracker.seed(podWithUid("pod-a", "uid-1"))
            coEvery { remoteIdeServer.refreshPod() } throws IOException("not running")

            val rollCount = AtomicInteger(0)
            tracker.onPodRoll = { rollCount.incrementAndGet() }

            val result = tracker.resolvePod()

            assertThat(result).isEqualTo(PodResolution.Unavailable)
            assertThat(rollCount.get()).isZero()
        }
    }

    @Test
    fun `#resolvePod uses pod name as fallback when uid is null`() {
        runBlocking {
            val podA = podWithNameOnly("pod-a")
            val podB = podWithNameOnly("pod-b")
            tracker.seed(podA)
            coEvery { remoteIdeServer.refreshPod() } returns podB

            var rolled = false
            tracker.onPodRoll = { rolled = true }

            tracker.resolvePod()

            assertThat(rolled).isTrue()
        }
    }

    @Test
    fun `#resolvePod does not fire onPodRoll when workspace restart is in progress`() {
        runBlocking {
            val podA = podWithUid("pod-a", "uid-1")
            val podB = podWithUid("pod-b", "uid-2")
            tracker.seed(podA)
            coEvery { remoteIdeServer.refreshPod() } returns podB

            val rollCount = AtomicInteger(0)
            val trackingTracker = WorkspacePodTracker(
                remoteIdeServer,
                isWorkspaceRestartInProgress = { true },
            )
            trackingTracker.seed(podA)
            trackingTracker.onPodRoll = { rollCount.incrementAndGet() }

            val result = trackingTracker.resolvePod()

            assertThat(result).isEqualTo(PodResolution.RestartSuppressed)
            assertThat(rollCount.get()).isZero()
        }
    }

    @Test
    fun `#resolvePod does not fire onPodRoll twice during re-entrant resolvePod`() {
        runBlocking {
            val podA = podWithUid("pod-a", "uid-1")
            val podB = podWithUid("pod-b", "uid-2")
            tracker.seed(podA)
            coEvery { remoteIdeServer.refreshPod() } returns podB

            val rollCount = AtomicInteger(0)
            tracker.onPodRoll = {
                rollCount.incrementAndGet()
                tracker.resolvePod()
            }

            tracker.resolvePod()

            assertThat(rollCount.get()).isEqualTo(1)
        }
    }

    private fun podWithUid(name: String, uid: String): V1Pod =
        V1Pod().metadata(V1ObjectMeta().name(name).uid(uid))

    private fun podWithNameOnly(name: String): V1Pod =
        V1Pod().metadata(V1ObjectMeta().name(name))
}
