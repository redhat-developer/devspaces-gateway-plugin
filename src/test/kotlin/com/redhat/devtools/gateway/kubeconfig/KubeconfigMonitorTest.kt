

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

import com.redhat.devtools.gateway.openshift.kube.Cluster
import com.redhat.devtools.gateway.openshift.kube.KubeConfigUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
class KubeconfigMonitorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testScope: TestScope
    private lateinit var mockKubeconfigFileWatcher: KubeconfigFileWatcher
    private lateinit var mockKubeConfigBuilder: KubeConfigUtils
    private lateinit var kubeconfigMonitor: KubeconfigMonitor

    private lateinit var kubeconfigPath1: Path
    private lateinit var kubeconfigPath2: Path

    @BeforeEach
    fun setup() {
        //given
        testScope = TestScope(StandardTestDispatcher())
        mockKubeconfigFileWatcher = mockk(relaxed = true)
        mockKubeConfigBuilder = mockk(relaxed = true)

        kubeconfigPath1 = tempDir.resolve("kubeconfig1.yaml").createFile()
        kubeconfigPath2 = tempDir.resolve("kubeconfig2.yaml").createFile()

        every { mockKubeconfigFileWatcher.start() } answers {
            // Simulate the watcher calling the monitor's onFileChanged when a file changes
        }

        kubeconfigMonitor = KubeconfigMonitor(testScope, mockKubeconfigFileWatcher, mockKubeConfigBuilder)
    }

    @AfterEach
    fun teardown() {
        kubeconfigMonitor.stop()
    }

    @Test
    fun `#start should initially parse and publish clusters`() = testScope.runTest {
        //given
        val cluster1 = Cluster("id1", "cluster1", "url1", null, null)
        every { mockKubeConfigBuilder.getAllConfigs() } returns listOf(kubeconfigPath1)
        every { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath1)) } returns listOf(cluster1)

        //when
        kubeconfigMonitor.start()
        advanceUntilIdle()

        //then
        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster1)
        verify(exactly = 1) { mockKubeconfigFileWatcher.addFile(kubeconfigPath1) }
        verify(exactly = 1) { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath1)) }
    }

    @Test
    fun `#onFileChanged should reparse and publish updated clusters`() = testScope.runTest {
        //given
        val cluster1 = Cluster("id1", "cluster1", "url1", null, null)
        val cluster1Updated = Cluster("id1", "cluster1", "url1", null, "token1")

        every { mockKubeConfigBuilder.getAllConfigs() } returns listOf(kubeconfigPath1)
        every { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath1)) } returns listOf(cluster1)

        kubeconfigMonitor.start()
        advanceUntilIdle()
        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster1)

        //when
        every { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath1)) } returns listOf(cluster1Updated)
        kubeconfigMonitor.onFileChanged(kubeconfigPath1) // Simulate watcher callback
        advanceUntilIdle()

        //then
        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster1Updated)
        verify(exactly = 2) { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath1)) } // Initial + update
    }

    @Test
    fun `#updateMonitoredPaths should add and remove files based on KUBECONFIG env var`() = testScope.runTest {
        //given
        val cluster1 = Cluster("id1", "cluster1", "url1", null, null)
        val cluster2 = Cluster("id2", "cluster2", "url2", null, null)

        // Initial KUBECONFIG
        every { mockKubeConfigBuilder.getAllConfigs() } returns listOf(kubeconfigPath1)
        every { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath1)) } returns listOf(cluster1)
        kubeconfigMonitor.start()
        advanceUntilIdle()
        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster1)
        verify(exactly = 1) { mockKubeconfigFileWatcher.addFile(kubeconfigPath1) }

        //when: Change KUBECONFIG to include kubeconfigPath2 and remove kubeconfigPath1
        every { mockKubeConfigBuilder.getAllConfigs() } returns listOf(kubeconfigPath2)
        every { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath2)) } returns listOf(cluster2)

        // Manually trigger updateMonitoredPaths and reparse
        kubeconfigMonitor.updateMonitoredPaths()
        kubeconfigMonitor.refreshClusters()
        advanceUntilIdle()

        //then
        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster2)
        verify(exactly = 1) { mockKubeconfigFileWatcher.removeFile(kubeconfigPath1) }
        verify(exactly = 1) { mockKubeconfigFileWatcher.addFile(kubeconfigPath2) }
        verify(exactly = 1) { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath2)) }
    }
}