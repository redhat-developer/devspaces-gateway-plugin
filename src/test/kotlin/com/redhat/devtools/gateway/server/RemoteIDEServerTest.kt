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
import io.kubernetes.client.openapi.models.V1PodSpec
import io.mockk.*
import kotlinx.coroutines.CancellationException
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

    @BeforeEach
    fun beforeEach() {
        devSpacesContext = mockk(relaxed = true)

        mockkConstructor(DevWorkspacePods::class)
        val mockPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "test-pod"
            }
            spec = V1PodSpec().apply {
                containers = listOf(
                    V1Container().apply {
                        name = "test-container"
                        ports = listOf(
                            mockk(relaxed = true) {
                                every { name } returns "idea-server"
                            }
                        )
                    }
                )
            }
        }
        coEvery {
            anyConstructed<DevWorkspacePods>().findFirst(any(), any())
        } returns mockPod

        remoteIDEServer = spyk(RemoteIDEServer(devSpacesContext), recordPrivateCalls = true)
    }

    @AfterEach
    fun afterEach() {
        unmockkAll()
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
            remoteIDEServer.getStatus()
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
            remoteIDEServer.getStatus()
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
            remoteIDEServer.getStatus()
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
            remoteIDEServer.getStatus()
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
            remoteIDEServer.getStatus()
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
            remoteIDEServer.getStatus()
        } throws IOException("error")

        // when
        val result = runBlocking {
            remoteIDEServer.waitServerTerminated(1)
        }

        // then
        assertThat(result).isFalse
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

    @Test
    fun `#getStatus skips exec when pod phase is not Running`() {
        // given — isPodRunning returns false for non-Running phases (Pending, Failed, etc.)
        coEvery {
            anyConstructed<DevWorkspacePods>().isPodRunning(any())
        } returns false

        // when
        val result = runBlocking {
            remoteIDEServer.getStatus()
        }

        // then — exec should never be called, preventing a long hang on stuck pod
        assertThat(result).isEqualTo(RemoteIDEServerStatus.empty())
        coVerify(exactly = 0) {
            anyConstructed<DevWorkspacePods>().exec(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `#getStatus throws CancellationException before checking pod running`() {
        // given — checkCancelled throws immediately
        coEvery {
            anyConstructed<DevWorkspacePods>().isPodRunning(any())
        } returns true

        // when / then
        assertThrows<CancellationException> {
            runBlocking {
                remoteIDEServer.getStatus { throw CancellationException("User cancelled") }
            }
        }

        // then — neither isPodRunning nor exec should have been called
        coVerify(exactly = 0) {
            anyConstructed<DevWorkspacePods>().isPodRunning(any())
        }
        coVerify(exactly = 0) {
            anyConstructed<DevWorkspacePods>().exec(any(), any(), any(), any(), any())
        }
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
