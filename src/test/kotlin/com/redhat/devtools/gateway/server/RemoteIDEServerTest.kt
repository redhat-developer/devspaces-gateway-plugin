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
import com.redhat.devtools.gateway.testutil.podWith
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1ContainerPort
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodCondition
import io.kubernetes.client.openapi.models.V1PodList
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
        val mockPod = podWith("test-pod").apply {
            spec = V1PodSpec().apply { containers = listOf(ideContainer("test-container")) }
        }
        resolvedPod = mockPod
        coEvery {
            anyConstructed<DevWorkspacePods>().findServingPod(any(), any(), any())
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
            anyConstructed<DevWorkspacePods>().findServingPod(
                "other-namespace",
                "other-workspace",
                requireReady = false
            )
        }
    }

    @Test
    fun `#constructor tolerates a running pod that is not ready yet`() {
        // given — the workspace pod still has to pass its readiness probe: a connect must wait for
        // it instead of failing as if the workspace were not running
        unmockkConstructor(DevWorkspacePods::class)
        mockkConstructor(CoreV1Api::class)
        stubPodList(
            listOf(
                podWith("starting-pod").apply {
                    spec = V1PodSpec().apply { containers = listOf(ideContainer("test-container")) }
                    markNotReady()
                }
            )
        )

        // when, then
        assertDoesNotThrow {
            RemoteIDEServer(devSpacesContext, mockWorkspace())
        }
    }

    @Test
    fun `#constructor throws when only a terminating pod matches`() {
        // given — the workspace is not running (a terminating pod must not be used)
        unmockkConstructor(DevWorkspacePods::class)
        mockkConstructor(CoreV1Api::class)
        stubPodList(
            listOf(
                podWith(
                    "terminating-pod",
                    deletionTimestamp = OffsetDateTime.parse("2026-01-01T10:05:00Z"),
                )
            )
        )
        val workspace = mockWorkspace()

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
        stubStatus(statusWithoutJoinLink())

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
        resolvedPod = podWith("test-pod").apply {
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
        resolvedPod = podWith("new-pod").apply {
            spec = V1PodSpec().apply { containers = listOf(ideContainer("new-container")) }
        }
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
        stubStatus(statusWithoutProjects())

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
        stubStatus(statusWithoutJoinLink())

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
        stubStatus(statusWithoutProjects())

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
        stubStatus(statusWithJoinLinkAndProject())

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

    /** A status with no join link but one project: a server that has not started serving. */
    private fun statusWithoutJoinLink(): RemoteIDEServerStatus =
        remoteIDEServerStatus(null, arrayOf(projectInfo("death star")))

    /** A status with a join link but no projects: a server that is already serving. */
    private fun statusWithoutProjects(): RemoteIDEServerStatus =
        remoteIDEServerStatus("https://starwars.galaxy?peridea", null)

    /** A status with both a join link and a project: a fully running server. */
    private fun statusWithJoinLinkAndProject(): RemoteIDEServerStatus =
        remoteIDEServerStatus("https://starwars.galaxy?peridea", arrayOf(projectInfo("death star")))

    /** Stubs the status the readiness and termination waits poll. */
    private fun stubStatus(status: RemoteIDEServerStatus) {
        coEvery { remoteIDEServer.getStatus() } returns status
    }

    private fun mockWorkspace(
        namespace: String = "test-namespace",
        name: String = "test-workspace"
    ): DevWorkspace = mockk {
        every { this@mockk.namespace } returns namespace
        every { this@mockk.name } returns name
    }

    /** Stubs the pod list the constructor's pod lookup reads. */
    private fun stubPodList(pods: List<V1Pod>) {
        val listRequest = mockk<CoreV1Api.APIlistNamespacedPodRequest>()
        every { listRequest.labelSelector(any()) } returns listRequest
        every { listRequest.execute() } returns V1PodList().apply { items = pods }
        every { anyConstructed<CoreV1Api>().listNamespacedPod(any()) } returns listRequest
    }

    /** Marks the pod as still failing its readiness probe. */
    private fun V1Pod.markNotReady() {
        status?.conditions = listOf(
            V1PodCondition().apply {
                type = "Ready"
                status = "False"
            }
        )
    }

    /** A container that publishes the idea-server port, so the pod lookup accepts it. */
    private fun ideContainer(containerName: String): V1Container = V1Container().apply {
        name = containerName
        ports = listOf(
            mockk(relaxed = true) {
                every { name } returns "idea-server"
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
