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
package com.redhat.devtools.gateway.kubeconfig

import com.redhat.devtools.gateway.openshift.Cluster
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile

@ExperimentalCoroutinesApi
class KubeConfigMonitorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope
    private lateinit var fileWatcher: FileWatcher
    private lateinit var mockKubeConfigUtils: KubeConfigUtils
    private lateinit var kubeconfigMonitor: KubeConfigMonitor

    private lateinit var kubeconfigPath1: Path
    private lateinit var kubeconfigPath2: Path

    @BeforeEach
    fun beforeEach() {
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        // Real FileWatcher for path tracking; stub start() so its infinite poll/delay loop
        // does not run on the test dispatcher (advanceUntilIdle would never complete).
        fileWatcher = spyk(FileWatcher(testScope, testDispatcher))
        justRun { fileWatcher.start() }
        mockKubeConfigUtils = mockk(relaxed = true)

        kubeconfigPath1 = tempDir.resolve("kubeconfig1.yaml").createFile()
        kubeconfigPath2 = tempDir.resolve("kubeconfig2.yaml").createFile()

        kubeconfigMonitor = KubeConfigMonitor(testScope, fileWatcher, mockKubeConfigUtils)
    }

    @AfterEach
    fun afterEach() {
        kubeconfigMonitor.stop()
    }

    @Test
    fun `#start should initially parse and publish clusters`() = runTest(testDispatcher) {
        val cluster1 = Cluster(name = "skywalker", url = "url1", token = null)
        every { mockKubeConfigUtils.getAllConfigFiles(any()) } returns listOf(kubeconfigPath1)
        every { mockKubeConfigUtils.getClusters(listOf(kubeconfigPath1)) } returns listOf(cluster1)

        kubeconfigMonitor.start()
        advanceUntilIdle()

        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster1)
        assertThat(fileWatcher.getMonitoredFiles()).containsExactly(kubeconfigPath1)
        // addFile notify + barrier refreshClusters coalesce to one parse of the full set
        verify(exactly = 1) { mockKubeConfigUtils.getClusters(listOf(kubeconfigPath1)) }
    }

    @Test
    fun `#start coalesces multi-file addFile notifies into one full-list parse`() = runTest(testDispatcher) {
        val cluster1 = Cluster(name = "skywalker", url = "url1", token = null)
        val cluster2 = Cluster(name = "obi-wan", url = "url2", token = null)

        every { mockKubeConfigUtils.getAllConfigFiles(any()) } returns listOf(kubeconfigPath1, kubeconfigPath2)
        every {
            mockKubeConfigUtils.getClusters(match { it.toSet() == setOf(kubeconfigPath1, kubeconfigPath2) })
        } returns listOf(cluster1, cluster2)

        kubeconfigMonitor.start()
        advanceUntilIdle()

        verify(exactly = 0) { mockKubeConfigUtils.getClusters(emptyList()) }
        verify(exactly = 0) {
            mockKubeConfigUtils.getClusters(match { it.size == 1 })
        }
        verify(exactly = 1) {
            mockKubeConfigUtils.getClusters(match { it.toSet() == setOf(kubeconfigPath1, kubeconfigPath2) })
        }
        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactlyInAnyOrder(cluster1, cluster2)
        assertThat(fileWatcher.getMonitoredFiles()).containsExactlyInAnyOrder(kubeconfigPath1, kubeconfigPath2)
    }

    @Test
    fun `#onFileChanged should reparse and publish updated clusters`() = runTest(testDispatcher) {
        val cluster1 = Cluster(name = "skywalker", url = "url1", token = null)
        val cluster1Updated = Cluster(name = "skywalker", url = "url1", token = "token1")

        every { mockKubeConfigUtils.getAllConfigFiles(any()) } returns listOf(kubeconfigPath1)
        every { mockKubeConfigUtils.getClusters(listOf(kubeconfigPath1)) } returns listOf(cluster1)

        kubeconfigMonitor.start()
        advanceUntilIdle()
        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster1)

        every { mockKubeConfigUtils.getClusters(listOf(kubeconfigPath1)) } returns listOf(cluster1Updated)
        kubeconfigMonitor.onFileChanged(kubeconfigPath1)
        advanceUntilIdle()

        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster1Updated)
        verify(exactly = 2) { mockKubeConfigUtils.getClusters(listOf(kubeconfigPath1)) }
    }

    @Test
    fun `#refreshClusters skips emit when cluster list is unchanged`() = runTest(testDispatcher) {
        val cluster1 = Cluster(name = "skywalker", url = "url1", token = null)
        every { mockKubeConfigUtils.getAllConfigFiles(any()) } returns listOf(kubeconfigPath1)
        every { mockKubeConfigUtils.getClusters(listOf(kubeconfigPath1)) } returns listOf(cluster1)

        kubeconfigMonitor.start()
        advanceUntilIdle()
        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster1)

        kubeconfigMonitor.refreshClusters()
        advanceUntilIdle()

        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster1)
        verify(exactly = 2) { mockKubeConfigUtils.getClusters(listOf(kubeconfigPath1)) }
    }

    @Test
    fun `#updateMonitoredPaths should add and remove files based on KUBECONFIG env var`() = runTest(testDispatcher) {
        val cluster1 = Cluster(name = "skywalker", url = "url1")
        val cluster2 = Cluster(name = "obi-wan", url = "url2")

        every { mockKubeConfigUtils.getAllConfigFiles(any()) } returns listOf(kubeconfigPath1)
        every { mockKubeConfigUtils.getClusters(listOf(kubeconfigPath1)) } returns listOf(cluster1)
        kubeconfigMonitor.start()
        advanceUntilIdle()
        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster1)
        assertThat(fileWatcher.getMonitoredFiles()).containsExactly(kubeconfigPath1)

        every { mockKubeConfigUtils.getAllConfigFiles(any()) } returns listOf(kubeconfigPath2)
        every { mockKubeConfigUtils.getClusters(listOf(kubeconfigPath2)) } returns listOf(cluster2)

        kubeconfigMonitor.updateMonitoredPaths()
        kubeconfigMonitor.refreshClusters()
        advanceUntilIdle()

        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster2)
        assertThat(fileWatcher.getMonitoredFiles()).containsExactly(kubeconfigPath2)
        verify(atLeast = 1) { mockKubeConfigUtils.getClusters(listOf(kubeconfigPath2)) }
    }

    @Test
    fun `#stop should not cancel the provided scope`() = runTest(testDispatcher) {
        val mockScope = mockk<TestScope>(relaxed = true)
        val monitor = KubeConfigMonitor(mockScope, fileWatcher, mockKubeConfigUtils)

        every { mockScope.cancel() } returns Unit

        monitor.stop()
        advanceUntilIdle()

        verify(exactly = 0) {
            mockScope.cancel()
        }
    }
}
