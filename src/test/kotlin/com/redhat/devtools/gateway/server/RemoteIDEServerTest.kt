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
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import com.redhat.devtools.gateway.openshift.PodExecSession
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1ContainerPort
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodCondition
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.openapi.models.V1PodSpec
import io.kubernetes.client.openapi.models.V1PodStatus
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
import java.time.OffsetDateTime

class RemoteIDEServerTest {

    private lateinit var devSpacesContext: DevSpacesContext
    private lateinit var remoteIDEServer: RemoteIDEServer

    /** The pod the workspace currently reports; a test swaps it to emulate a pod roll. */
    private var resolvedPod: V1Pod? = null

    @BeforeEach
    fun beforeEach() {
        devSpacesContext = mockk(relaxed = true)

        mockkConstructor(DevWorkspacePods::class)
        mockkConstructor(PodExecSession::class)
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
        resolvedPod = mockPod
        coEvery {
            anyConstructed<DevWorkspacePods>().findFirstRunning(any(), any())
        } answers { resolvedPod }

        remoteIDEServer = spyk(RemoteIDEServer(devSpacesContext), recordPrivateCalls = true)
    }

    @Test
    fun `#constructor looks up the pod of the workspace it was given`() {
        // given
        val workspace = mockk<DevWorkspace> {
            every { namespace } returns "other-namespace"
            every { name } returns "other-workspace"
        }

        // when
        RemoteIDEServer(devSpacesContext, workspace)

        // then
        verify {
            anyConstructed<DevWorkspacePods>().findFirstRunning(
                "other-namespace",
                "controller.devfile.io/devworkspace_name=other-workspace"
            )
        }
    }

    @Test
    fun `#findPod does not pick a terminating pod`() {
        // given — a pod roll: the old pod is still listed first and reports Running, but it is
        // terminating; only the newer pod is ready to serve.
        unmockkConstructor(DevWorkspacePods::class)
        mockkConstructor(CoreV1Api::class)
        val terminatingPod = podWith(
            podName = "terminating-pod",
            creationTimestamp = OffsetDateTime.parse("2026-01-01T10:00:00Z"),
            deletionTimestamp = OffsetDateTime.parse("2026-01-01T10:05:00Z"),
        )
        val runningPod = podWith(
            podName = "running-pod",
            creationTimestamp = OffsetDateTime.parse("2026-01-01T10:04:00Z"),
        )
        val listRequest = mockk<CoreV1Api.APIlistNamespacedPodRequest>()
        every { listRequest.labelSelector(any()) } returns listRequest
        every {
            listRequest.execute()
        } returns V1PodList().apply { items = listOf(terminatingPod, runningPod) }
        every { anyConstructed<CoreV1Api>().listNamespacedPod(any()) } returns listRequest
        val workspace = mockk<DevWorkspace> {
            every { namespace } returns "test-namespace"
            every { name } returns "test-workspace"
        }

        // when
        val server = RemoteIDEServer(devSpacesContext, workspace)

        // then — the running pod serves, the terminating one is skipped
        assertThat(server.pod.metadata?.name).isEqualTo("running-pod")
    }

    @Test
    fun `#constructor throws when only a terminating pod matches`() {
        // given — the workspace is not running (a terminating pod must not be used)
        unmockkConstructor(DevWorkspacePods::class)
        mockkConstructor(CoreV1Api::class)
        val listRequest = mockk<CoreV1Api.APIlistNamespacedPodRequest>()
        every { listRequest.labelSelector(any()) } returns listRequest
        every {
            listRequest.execute()
        } returns V1PodList().apply {
            items = listOf(
                podWith(
                    podName = "terminating-pod",
                    deletionTimestamp = OffsetDateTime.parse("2026-01-01T10:05:00Z"),
                )
            )
        }
        every { anyConstructed<CoreV1Api>().listNamespacedPod(any()) } returns listRequest
        val workspace = mockk<DevWorkspace> {
            every { namespace } returns "test-namespace"
            every { name } returns "test-workspace"
        }

        // when
        val exception = assertThrows<IOException> {
            RemoteIDEServer(devSpacesContext, workspace)
        }

        // then
        assertThat(exception.message).isEqualTo("DevWorkspace 'test-workspace' is not running.")
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
    fun `#waitServerReady fails fast when idea-server container is missing`() {
        // given — refreshing the target during the ready-wait resolves a pod without an
        // idea-server container (the workspace's container spec changed under a pod roll)
        resolvedPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply { name = "test-pod" }
            spec = V1PodSpec().apply {
                containers = listOf(
                    V1Container().apply {
                        name = "test-container"
                        // no idea-server port: the container cannot serve the IDE
                        ports = listOf(V1ContainerPort().apply { name = "http" })
                    }
                )
            }
        }

        // when
        val exception = assertThrows<IOException> {
            runBlocking {
                remoteIDEServer.waitServerReady(timeout = 5)
            }
        }

        // then — fails immediately instead of retrying until the ready timeout elapses
        assertThat(exception.message).contains("Workspace IDE container not found")
    }

    @Test
    fun `#refreshPod swaps the pod and its container together`() {
        // given — the IDE server moved to a new pod whose container is in a different state
        resolvedPod = runningPod("new-pod", containerName = "new-container")
        assertThat(remoteIDEServer.pod.metadata?.name).isEqualTo("test-pod")
        assertThat(remoteIDEServer.ideContainerName).isEqualTo("test-container")

        // when
        val found = remoteIDEServer.refreshPod()

        // then — the previous pod's container must not survive onto the new pod
        assertThat(found).isTrue()
        assertThat(remoteIDEServer.pod.metadata?.name).isEqualTo("new-pod")
        assertThat(remoteIDEServer.ideContainerName).isEqualTo("new-container")
    }

    @Test
    fun `#refreshPod keeps the previous target when no pod is running`() {
        // given — the workspace has no running, ready pod right now
        resolvedPod = null

        // when
        val found = remoteIDEServer.refreshPod()

        // then — nothing is swapped, the target stays what it was
        assertThat(found).isFalse()
        assertThat(remoteIDEServer.pod.metadata?.name).isEqualTo("test-pod")
        assertThat(remoteIDEServer.ideContainerName).isEqualTo("test-container")
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

    private fun podWith(
        podName: String,
        creationTimestamp: OffsetDateTime? = null,
        deletionTimestamp: OffsetDateTime? = null,
    ): V1Pod {
        return V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                this.name = podName
                this.creationTimestamp = creationTimestamp
                this.deletionTimestamp = deletionTimestamp
            }
            spec = V1PodSpec().apply {
                containers = listOf(
                    V1Container().apply {
                        this.name = "test-container"
                        ports = listOf(
                            mockk(relaxed = true) {
                                every { name } returns "idea-server"
                            }
                        )
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

    /** A running, ready pod that serves the IDE on [containerName]: what a pod roll produces. */
    private fun runningPod(podName: String, containerName: String): V1Pod =
        podWith(podName).apply {
            spec!!.containers = listOf(
                V1Container().apply {
                    this.name = containerName
                    ports = listOf(
                        mockk(relaxed = true) {
                            every { name } returns "idea-server"
                        }
                    )
                }
            )
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
            anyConstructed<PodExecSession>().execute()
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
            anyConstructed<PodExecSession>().execute()
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
