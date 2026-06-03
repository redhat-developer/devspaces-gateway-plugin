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
package com.redhat.devtools.gateway.server

import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodCondition
import io.kubernetes.client.openapi.models.V1PodSpec
import io.kubernetes.client.openapi.models.V1PodStatus
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class RemoteIDEServerTest {

    private lateinit var devSpacesContext: DevSpacesContext
    private lateinit var remoteIDEServer: RemoteIDEServer
    private lateinit var mockPod: V1Pod

    @BeforeEach
    fun beforeEach() {
        devSpacesContext = mockk(relaxed = true)

        mockkConstructor(DevWorkspacePods::class)
        mockPod = runningPod("test-pod")
        every {
            anyConstructed<DevWorkspacePods>().findFirstRunning(any(), any())
        } returns mockPod

        remoteIDEServer = spyk(RemoteIDEServer(devSpacesContext), recordPrivateCalls = true)
    }

    @AfterEach
    fun afterEach() {
        unmockkAll()
    }

    @Test
    fun `#getPod queries cluster and returns cached pod on subsequent calls`() {
        // given
        // when
        val first = remoteIDEServer.getPod()
        val second = remoteIDEServer.getPod()
        // then
        assertThat(first.metadata?.name).isEqualTo("test-pod")
        assertThat(second).isSameAs(first)
        verify(exactly = 1) {
            anyConstructed<DevWorkspacePods>().findFirstRunning(any(), any())
        }
    }

    @Test
    fun `#getContainer returns IDE container from workspace pod`() {
        // given
        // when
        val container = remoteIDEServer.getContainer()
        // then
        assertThat(container.name).isEqualTo("test-container")
        verify(exactly = 1) {
            anyConstructed<DevWorkspacePods>().findFirstRunning(any(), any())
        }
    }

    @Test
    fun `#refreshPod re-queries cluster and returns new pod when it changes`() {
        // given
        val firstPod = runningPod("pod-v1")
        val secondPod = runningPod("pod-v2")
        every {
            anyConstructed<DevWorkspacePods>().findFirstRunning(any(), any())
        } returnsMany listOf(firstPod, secondPod)
        // when
        val refreshedPod1 = remoteIDEServer.refreshPod()
        val refreshedPod2 = remoteIDEServer.refreshPod()
        // then
        assertThat(refreshedPod1.metadata?.name).isEqualTo(firstPod.metadata?.name)
        assertThat(refreshedPod2.metadata?.name).isEqualTo(secondPod.metadata?.name)
    }

    @Test
    fun `#refreshPod throws when no running pod exists`() {
        // given
        every {
            anyConstructed<DevWorkspacePods>().findFirstRunning(any(), any())
        } returns null
        // then
        assertThrows<IOException> {
            remoteIDEServer.refreshPod()
        }
    }

    @Test
    fun `#setPod seeds cache without querying cluster`() {
        // when
        val pod = runningPod("cached-pod")
        remoteIDEServer.setPod(pod)
        // when
        val result = remoteIDEServer.getPod()
        // then
        assertThat(result.metadata?.name).isEqualTo("cached-pod")
        assertThat(result).isSameAs(pod)
        verify(exactly = 0) {
            anyConstructed<DevWorkspacePods>().findFirstRunning(any(), any())
        }
    }

    @Test
    fun `#fetchStatus uses short exec timeout for status probes`() {
        val cachedPod = runningPod("cached-pod")
        remoteIDEServer.setPod(cachedPod)
        val readyJson = """{"joinLink":"https://ready","httpLink":"","gatewayLink":"","appVersion":"","runtimeVersion":"","projects":[]}"""
        val execTimeout = slot<Long>()
        coEvery {
            anyConstructed<DevWorkspacePods>().exec(any(), any(), any(), capture(execTimeout), any())
        } returns readyJson

        runBlocking {
            remoteIDEServer.fetchStatus()
        }

        assertThat(execTimeout.captured).isEqualTo(RemoteIDEServer.STATUS_EXEC_TIMEOUT)
    }

    @Test
    fun `#waitServerReady error message uses configured timeout`() {
        coEvery {
            remoteIDEServer.fetchStatus(checkCancelled = any())
        } returns remoteIDEServerStatus(null, arrayOf(projectInfo("death star")))

        val error = assertThrows<IOException> {
            runBlocking {
                remoteIDEServer.waitServerReady(timeout = 5)
            }
        }

        assertThat(error.message).isEqualTo("Workspace IDE is not ready after 5 seconds.")
    }

    @Test
    fun `#fetchStatus uses cached pod after setPod`() {
        // given
        val cachedPod = runningPod("cached-pod")
        remoteIDEServer.setPod(cachedPod)
        val readyJson = """{"joinLink":"https://ready","httpLink":"","gatewayLink":"","appVersion":"","runtimeVersion":"","projects":[]}"""
        var execPod: V1Pod? = null
        coEvery {
            anyConstructed<DevWorkspacePods>().exec(any(), any(), any(), any(), any())
        } answers {
            execPod = firstArg<V1Pod>()
            readyJson
        }
        // when
        runBlocking {
            remoteIDEServer.fetchStatus()
        }

        verify(exactly = 0) {
            anyConstructed<DevWorkspacePods>().findFirstRunning(any(), any())
        }
        // then
        assertThat(execPod).isSameAs(cachedPod)
    }

    @Test
    fun `#fetchStatus does not call refreshPod after setPod`() {
        // given
        val cachedPod = runningPod("cached-pod")
        remoteIDEServer.setPod(cachedPod)
        val readyJson = """{"joinLink":"https://ready","httpLink":"","gatewayLink":"","appVersion":"","runtimeVersion":"","projects":[]}"""
        var execPod: V1Pod? = null
        coEvery {
            anyConstructed<DevWorkspacePods>().exec(any(), any(), any(), any(), any())
        } answers {
            execPod = firstArg<V1Pod>()
            readyJson
        }
        // when
        runBlocking {
            remoteIDEServer.fetchStatus()
        }

        verify(exactly = 0) {
            anyConstructed<DevWorkspacePods>().findFirstRunning(any(), any())
        }
        // then
        assertThat(execPod).isSameAs(cachedPod)
    }

    @Test
    fun `#awaitJoinLink seeds pod waits for ready server and returns join link`() = runBlocking {
        val pod = runningPod("rolled-pod")
        coEvery { remoteIDEServer.waitServerReady(checkCancelled = any(), timeout = any()) } returns true
        coEvery { remoteIDEServer.fetchStatus(checkCancelled = any()) } returns RemoteIDEServerStatus(
            "https://join",
            "",
            "",
            "",
            "",
            null,
        )

        val link = remoteIDEServer.awaitJoinLink(pod)

        assertThat(link).isEqualTo("https://join")
        verify { remoteIDEServer.setPod(pod) }
        coVerify { remoteIDEServer.waitServerReady(checkCancelled = any(), timeout = any()) }
    }

    @Test
    fun `#waitServerReady should reach timeout and throw if server status has no join link`() {
        // given
        val withoutJoinLink = remoteIDEServerStatus(
            null,
            arrayOf(
                projectInfo("death star")
            )
        )
        coEvery {
            remoteIDEServer.fetchStatus()
        } returns withoutJoinLink

        // when, then
        assertThrows<IOException> {
            runBlocking {
                remoteIDEServer.waitServerReady(timeout = 1)
            }
        }
    }

    @Test
    fun `#waitServerReady should NOT reach timeout and throw if server status has a join link but no projects`() {
        // given
        val withoutProjects = remoteIDEServerStatus(
            "https://starwars.galaxy?peridea",
            null
        )
        coEvery {
            remoteIDEServer.fetchStatus()
        } returns withoutProjects

        // when, then
        assertDoesNotThrow {
            runBlocking {
                remoteIDEServer.waitServerReady(timeout = 1)
            }
        }
    }

    @Test
    fun `#waitServerTerminated should return true if server status has no join link`() {
        // given
        val withoutJoinLink = remoteIDEServerStatus(
            null,
            arrayOf(
                projectInfo("death star")
            )
        )
        coEvery {
            remoteIDEServer.fetchStatus()
        } returns withoutJoinLink

        // when
        val result = runBlocking {
            remoteIDEServer.waitServerTerminated()
        }

        // then
        assertThat(result).isTrue
    }

    @Test
    fun `#waitServerTerminated should return false if server status has a join link but no projects`() {
        // given
        val withoutProjects = remoteIDEServerStatus(
            "https://starwars.galaxy?peridea",
            null
        )
        coEvery {
            remoteIDEServer.fetchStatus()
        } returns withoutProjects

        // when
        val result = runBlocking {
            remoteIDEServer.waitServerTerminated(1)
        }

        // then
        assertThat(result).isFalse
    }

    @Test
    fun `#waitServerTerminated should return false on timeout`() {
        // given
        coEvery {
            remoteIDEServer.fetchStatus()
        } returns remoteIDEServerStatus(
            // running server has join link and projects
            "https://starwars.galaxy?peridea",
            arrayOf(
                projectInfo("death star")
            )
        )

        // when
        val result = runBlocking {
            remoteIDEServer.waitServerTerminated(1)
        }

        // then
        assertThat(result).isFalse
    }

    @Test
    fun `#waitServerTerminated should return false on exception`() {
        // given
        coEvery {
            remoteIDEServer.fetchStatus()
        } throws IOException("error")

        // when
        val result = runBlocking {
            remoteIDEServer.waitServerTerminated(1)
        }

        // then
        assertThat(result).isFalse
    }

    private fun runningPod(name: String): V1Pod {
        return V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                this.name = name
                uid = name
            }
            spec = V1PodSpec().apply {
                containers = listOf(
                    V1Container().apply {
                        this.name = "test-container"
                        ports = listOf(mockk(relaxed = true))
                    }
                )
            }
            status = V1PodStatus().apply {
                phase = "Running"
                conditions = listOf(
                    V1PodCondition().apply {
                        type = "Ready"
                        status = "True"
                    }
                )
            }
        }
    }

    private fun remoteIDEServerStatus(joinLink: String? = null, projects: Array<ProjectInfo>?): RemoteIDEServerStatus {
        return RemoteIDEServerStatus(
            joinLink,
            "",
            "",
            "",
            "",
            projects
        )
    }

    private fun projectInfo(name: String): ProjectInfo {
        return ProjectInfo(
            name,
            name,
            name,
            name,
            name
        )

    }

}
