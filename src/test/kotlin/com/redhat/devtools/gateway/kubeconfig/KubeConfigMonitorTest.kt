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
import com.redhat.devtools.gateway.kubeconfig.FileWatcher
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import com.redhat.devtools.gateway.kubeconfig.KubeConfigMonitor
import com.redhat.devtools.gateway.openshift.Cluster
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
class KubeConfigMonitorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testScope: TestScope
    private lateinit var mockFileWatcher: FileWatcher
    private lateinit var mockKubeConfigBuilder: KubeConfigUtils
    private lateinit var kubeconfigMonitor: KubeConfigMonitor

    private lateinit var kubeconfigPath1: Path
    private lateinit var kubeconfigPath2: Path

    @BeforeEach
    fun beforeEach() {
        // given
        testScope = TestScope(StandardTestDispatcher())
        mockFileWatcher = mockk(relaxed = true)
        mockKubeConfigBuilder = mockk(relaxed = true)

        kubeconfigPath1 = tempDir.resolve("kubeconfig1.yaml").createFile()
        kubeconfigPath2 = tempDir.resolve("kubeconfig2.yaml").createFile()

        kubeconfigMonitor = KubeConfigMonitor(testScope, mockFileWatcher, mockKubeConfigBuilder)
    }

    @AfterEach
    fun afterEach() {
        kubeconfigMonitor.stop()
    }

    @Test
    fun `#start should initially parse and publish clusters`() = testScope.runTest {
        // given
        val cluster1 = Cluster("skywalker", "url1", null)
        every { mockKubeConfigBuilder.getAllConfigFiles(any()) } returns listOf(kubeconfigPath1)
        every { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath1)) } returns listOf(cluster1)

        // when
        kubeconfigMonitor.start()
        advanceUntilIdle()

        // then
        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster1)
        verify(exactly = 1) { mockFileWatcher.addFile(kubeconfigPath1) }
        verify(exactly = 1) { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath1)) }
    }

    @Test
    fun `#onFileChanged should reparse and publish updated clusters`() = testScope.runTest {
        // given
        val cluster1 = Cluster("skywalker", "url1", null)
        val cluster1Updated = Cluster("skywalker", "url1", "token1")

        every { mockKubeConfigBuilder.getAllConfigFiles(any()) } returns listOf(kubeconfigPath1)
        every { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath1)) } returns listOf(cluster1)

        kubeconfigMonitor.start()
        advanceUntilIdle()
        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster1)

        // when
        every { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath1)) } returns listOf(cluster1Updated)
        kubeconfigMonitor.onFileChanged(kubeconfigPath1) // Simulate watcher callback
        advanceUntilIdle()

        // then
        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster1Updated)
        verify(exactly = 2) { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath1)) } // Initial + update
    }

    @Test
    fun `#updateMonitoredPaths should add and remove files based on KUBECONFIG env var`() = testScope.runTest {
        // given
        val cluster1 = Cluster("skywalker", "url1")
        val cluster2 = Cluster("obi-wan", "url2")

        // Initial KUBECONFIG
        every { mockKubeConfigBuilder.getAllConfigFiles(any()) } returns listOf(kubeconfigPath1)
        every { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath1)) } returns listOf(cluster1)
        kubeconfigMonitor.start()
        advanceUntilIdle()
        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster1)
        verify(exactly = 1) { mockFileWatcher.addFile(kubeconfigPath1) }

        // when: Change KUBECONFIG to include kubeconfigPath2 and remove kubeconfigPath1
        every { mockKubeConfigBuilder.getAllConfigFiles(any()) } returns listOf(kubeconfigPath2)
        every { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath2)) } returns listOf(cluster2)

        // Manually trigger updateMonitoredPaths and reparse
        kubeconfigMonitor.updateMonitoredPaths()
        kubeconfigMonitor.refreshClusters()
        advanceUntilIdle()

        // then
        assertThat(kubeconfigMonitor.getCurrentClusters()).containsExactly(cluster2)
        verify(exactly = 1) {
            mockFileWatcher.removeFile(kubeconfigPath1)
            mockFileWatcher.addFile(kubeconfigPath2)
        }
        verify(exactly = 1) { mockKubeConfigBuilder.getClusters(listOf(kubeconfigPath2)) }
    }

    @Test
    fun `#stop should not cancel the provided scope`() = testScope.runTest {
        // given
        val mockScope = mockk<TestScope>(relaxed = true)
        val monitor = KubeConfigMonitor(mockScope, mockFileWatcher, mockKubeConfigBuilder)

        every { mockScope.cancel() } returns Unit

        // when
        monitor.stop()
        advanceUntilIdle()

        // then
        verify(exactly = 0) {
            mockScope.cancel()
        }
    }
}