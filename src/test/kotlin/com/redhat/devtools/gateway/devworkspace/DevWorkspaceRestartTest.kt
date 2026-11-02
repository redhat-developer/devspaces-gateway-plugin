/*
 * Copyright (c) 2026 Red Hat, Inc.
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

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.redhat.devtools.gateway.DevSpacesConnection
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.view.ui.Dialogs
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class DevWorkspaceRestartTest {

    private lateinit var devSpacesContext: DevSpacesContext
    private lateinit var workspaces: DevWorkspaces
    private lateinit var pods: DevWorkspacePods
    private lateinit var thinClient: ThinClientHandle
    private lateinit var indicator: ProgressIndicator
    private lateinit var remoteIDEServer: RemoteIDEServer
    private lateinit var devSpacesConnection: DevSpacesConnection

    private val namespace = "test-namespace"
    private val workspaceName = "test-workspace"

    private lateinit var restart: DevWorkspaceRestart

    @BeforeEach
    fun beforeEach() {
        devSpacesContext = mockk(relaxed = true) {
            every { devWorkspace.namespace } returns namespace
            every { devWorkspace.name } returns workspaceName
        }
        workspaces = mockk(relaxed = true)
        pods = mockk(relaxed = true)
        thinClient = mockk(relaxed = true)
        indicator = mockk(relaxed = true)
        remoteIDEServer = mockk(relaxed = true)
        devSpacesConnection = mockk(relaxed = true)

        restart = DevWorkspaceRestart(
            devSpacesContext,
            workspaces,
            pods,
            createRemoteIDEServer = { remoteIDEServer },
            createDevSpacesConnection = { devSpacesConnection }
        )

        // Default: no pods remaining
        every { pods.list(any(), any()) } returns V1PodList().items(emptyList())
        // Default: the workspace publishes a devfile template (a restart request always carries one)
        every { workspaces.get(namespace, workspaceName) } returns
            workspace(mapOf(DevWorkspace.PENDING_TEMPLATE_KEY to templatePayload))
        // Default: IDE ready and connection succeed (`just Awaits` never completes — hangs runTest)
        coJustRun { remoteIDEServer.waitServerReady() }
        coEvery { devSpacesConnection.connect(any(), any(), any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
    }

    @Test
    fun `#doRestart closes thin client`() = runTest {
        // when
        restart.restart(thinClient, indicator)
        // then
        verify { thinClient.close() }
    }

    @Test
    fun `#doRestart closes thin client before stopping workspace`() = runTest {
        // when
        restart.restart(thinClient, indicator)
        // then
        verifyOrder {
            thinClient.close()
            workspaces.stopAndWait(namespace, workspaceName, any())
        }
    }

    @Test
    fun `#doRestart stops workspace`() = runTest {
        // when
        restart.restart(thinClient, indicator)
        // then
        verify { workspaces.stopAndWait(namespace, workspaceName, DevWorkspaces.RUNNING_TIMEOUT) }
    }

    @Test
    fun `#doRestart waits for pods to be deleted`() = runTest {
        // when
        restart.restart(thinClient, indicator)
        // then
        verify { pods.list(namespace, "${DevWorkspacePods.WORKSPACE_LABEL_KEY}=$workspaceName") }
    }

    @Test
    fun `#doRestart continues when pods deleted`() = runTest {
        // given
        every { pods.list(any(), any()) } returns V1PodList().items(emptyList())
        // when
        restart.restart(thinClient, indicator)
        // then
        verify { workspaces.startAndWait(namespace, workspaceName, any()) }
    }

    @Test
    fun `#doRestart waits until pods with deletionTimestamp are deleted`() = runTest {
        // given
        val podWithDeletionTimestamp = mockk<V1Pod> {
            every { metadata?.deletionTimestamp } returns mockk()
            every { metadata?.name } returns "pod-1"
        }
        every { pods.list(any(), any()) } returns
            V1PodList().items(listOf(podWithDeletionTimestamp)) andThen
            V1PodList().items(emptyList())
        // when
        restart.restart(thinClient, indicator)
        // then
        verify(atLeast = 2) { pods.list(namespace, any()) }
    }

    @Test
    fun `#doRestart retries when pods still exist`() = runTest {
        // given
        val activePod = V1Pod().metadata(
            io.kubernetes.client.openapi.models.V1ObjectMeta().name("pod-1")
        )
        every { pods.list(any(), any()) } returns
            V1PodList().items(listOf(activePod)) andThen
            V1PodList().items(listOf(activePod)) andThen
            V1PodList().items(emptyList())
        // when
        restart.restart(thinClient, indicator)
        // then
        verify(atLeast = 3) { pods.list(namespace, any()) }
    }

    @Test
    fun `#doRestart throws when pods not deleted within timeout`() = runTest {
        // given
        val activePod = V1Pod().metadata(
            io.kubernetes.client.openapi.models.V1ObjectMeta().name("pod-1")
        )
        every { pods.list(any(), any()) } returns V1PodList().items(listOf(activePod))
        // when
        val result = runCatching { restart.restart(thinClient, indicator) }
        // then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.exceptionOrNull()).hasMessageContaining("Timeout waiting for pods deletion")
    }

    @Test
    fun `#doRestart starts workspace after pods deleted`() = runTest {
        // when
        restart.restart(thinClient, indicator)
        // then
        verifyOrder {
            workspaces.stopAndWait(namespace, workspaceName, any())
            workspaces.startAndWait(namespace, workspaceName, any())
        }
    }

    @Test
    fun `#doRestart waits for IDE ready`() = runTest {
        // when
        restart.restart(thinClient, indicator)
        // then
        coVerify { remoteIDEServer.waitServerReady() }
    }

    @Test
    fun `#doRestart connects to IDE`() = runTest {
        // when
        restart.restart(thinClient, indicator)
        // then
        coVerify { devSpacesConnection.connect(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `#doRestart connects to IDE after waiting for ready`() = runTest {
        // when
        restart.restart(thinClient, indicator)
        // then
        coVerifyOrder {
            remoteIDEServer.waitServerReady()
            devSpacesConnection.connect(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `#execute removes restart annotation after successful restart`() = runTest {
        every { workspaces.hasRestartAnnotation(namespace, workspaceName) } returns true
        runExecuteWithProgressMock {
            restart.execute(thinClient)
        }
        verify { workspaces.removeRestartAnnotation(namespace, workspaceName) }
    }

    @Test
    fun `#execute does not call removeRestarting when annotation already cleared`() = runTest {
        every { workspaces.hasRestartAnnotation(namespace, workspaceName) } returns false
        runExecuteWithProgressMock {
            restart.execute(thinClient)
        }
        verify(exactly = 0) { workspaces.removeRestartAnnotation(namespace, workspaceName) }
    }

    @Test
    fun `#execute completes when annotation removal fails`() = runTest {
        every { workspaces.hasRestartAnnotation(namespace, workspaceName) } returns true
        every { workspaces.removeRestartAnnotation(namespace, workspaceName) } throws ApiException("Remove failed")
        runExecuteWithProgressMock {
            restart.execute(thinClient)
        }
        verify { workspaces.startAndWait(namespace, workspaceName, any()) }
    }

    @Test
    fun `#doRestart executes full sequence in correct order`() = runTest {
        // when
        restart.restart(thinClient, indicator)
        // then
        verifyOrder {
            thinClient.close()
            workspaces.stopAndWait(namespace, workspaceName, any())
            pods.list(namespace, any())
            workspaces.startAndWait(namespace, workspaceName, any())
        }
        coVerifyOrder {
            remoteIDEServer.waitServerReady()
            devSpacesConnection.connect(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `#doRestart fails when thin client close fails`() = runTest {
        // given
        val exception = RuntimeException("Close failed")
        every { thinClient.close() } throws exception
        // when
        val result = runCatching { restart.restart(thinClient, indicator) }
        // then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isEqualTo(exception)
    }

    @Test
    fun `#doRestart fails when stop workspace fails`() = runTest {
        // given
        val exception = ApiException("Stop failed")
        every { workspaces.stopAndWait(any(), any(), any()) } throws exception
        // when
        val result = runCatching { restart.restart(thinClient, indicator) }
        // then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isEqualTo(exception)
    }

    @Test
    fun `#doRestart fails when start workspace fails`() = runTest {
        // given
        val exception = ApiException("Start failed")
        every { workspaces.startAndWait(any(), any(), any()) } throws exception
        // when
        val result = runCatching { restart.restart(thinClient, indicator) }
        // then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isEqualTo(exception)
    }

    @Test
    fun `#doRestart fails when IDE ready fails`() = runTest {
        // given
        val exception = RuntimeException("IDE not ready")
        coEvery { remoteIDEServer.waitServerReady() } throws exception
        // when
        val result = runCatching { restart.restart(thinClient, indicator) }
        // then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isEqualTo(exception)
    }

    @Test
    fun `#doRestart fails when IDE connection fails`() = runTest {
        // given
        val exception = RuntimeException("Connection failed")
        coEvery { devSpacesConnection.connect(any(), any(), any(), any(), any(), any(), any()) } throws exception

        // when/then
        val result = runCatching { restart.restart(thinClient, indicator) }
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isEqualTo(exception)
    }

    @Test
    fun `#doRestart retries pod listing on failure`() = runTest {
        // given
        every { pods.list(any(), any()) } throws RuntimeException("List failed") andThen
            V1PodList().items(emptyList())

        // when
        restart.restart(thinClient, indicator)

        // then
        verify(atLeast = 2) { pods.list(namespace, any()) }
        verify { workspaces.startAndWait(namespace, workspaceName, any()) }
    }

    @Test
    fun `#doRestart applies the published template before starting`() = runTest {
        // given
        every { workspaces.get(namespace, workspaceName) } returns
            workspace(mapOf(DevWorkspace.PENDING_TEMPLATE_KEY to templatePayload))

        // when
        restart.restart(thinClient, indicator)

        // then
        verifyOrder {
            workspaces.stopAndWait(namespace, workspaceName, any())
            workspaces.setSpecTemplate(namespace, workspaceName, any())
            workspaces.startAndWait(namespace, workspaceName, any())
        }
    }

    @Test
    fun `#doRestart refuses a request without a devfile template`() = runTest {
        // given
        every { workspaces.get(namespace, workspaceName) } returns workspace()

        // when
        val result = runCatching { restart.restart(thinClient, indicator) }

        // then
        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidRestartRequestException::class.java)
        verify(exactly = 0) { thinClient.close() }
        verify(exactly = 0) { workspaces.stopAndWait(any(), any(), any()) }
        verify(exactly = 0) { workspaces.setSpecTemplate(any(), any(), any()) }
    }

    @Test
    fun `#doRestart refuses a blank devfile template`() = runTest {
        // given
        every { workspaces.get(namespace, workspaceName) } returns
            workspace(mapOf(DevWorkspace.PENDING_TEMPLATE_KEY to "  "))

        // when
        val result = runCatching { restart.restart(thinClient, indicator) }

        // then
        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidRestartRequestException::class.java)
        verify(exactly = 0) { thinClient.close() }
        verify(exactly = 0) { workspaces.stopAndWait(any(), any(), any()) }
    }

    @Test
    fun `#doRestart aborts before closing when the published template is invalid`() = runTest {
        // given
        every { workspaces.get(namespace, workspaceName) } returns
            workspace(mapOf(DevWorkspace.PENDING_TEMPLATE_KEY to "{invalid"))

        // when
        val result = runCatching { restart.restart(thinClient, indicator) }

        // then
        assertThat(result.isFailure).isTrue()
        verify(exactly = 0) { thinClient.close() }
        verify(exactly = 0) { workspaces.stopAndWait(any(), any(), any()) }
    }

    @Test
    fun `#doRestart starts the workspace when applying the template fails`() = runTest {
        // given
        every { workspaces.get(namespace, workspaceName) } returns
            workspace(mapOf(DevWorkspace.PENDING_TEMPLATE_KEY to templatePayload))
        every { workspaces.setSpecTemplate(any(), any(), any()) } throws ApiException("apply failed")

        // when
        restart.restart(thinClient, indicator)

        // then
        verify { workspaces.startAndWait(namespace, workspaceName, any()) }
    }

    @Test
    fun `#doRestart propagates cancellation when applying the template is cancelled`() = runTest {
        // given
        every { workspaces.get(namespace, workspaceName) } returns
            workspace(mapOf(DevWorkspace.PENDING_TEMPLATE_KEY to templatePayload))
        every { workspaces.setSpecTemplate(any(), any(), any()) } throws
            CancellationException("cancelled")

        // when
        val result = runCatching { restart.restart(thinClient, indicator) }

        // then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        verify(exactly = 0) { workspaces.startAndWait(any(), any(), any()) }
    }

    @Test
    fun `#execute shows a restart error when the pending template is invalid`() = runTest {
        // given
        every { workspaces.get(namespace, workspaceName) } returns
            workspace(mapOf(DevWorkspace.PENDING_TEMPLATE_KEY to "{invalid"))

        // when/then
        runExecuteWithDialogsMock {
            restart.execute(thinClient)

            verify { Dialogs.error("Invalid devfile template payload", "Restart Workspace") }
            verify(exactly = 0) { thinClient.close() }
        }
    }

    @Test
    fun `#execute shows a restart error when no template is published`() = runTest {
        // given
        every { workspaces.get(namespace, workspaceName) } returns workspace()

        // when/then
        runExecuteWithDialogsMock {
            restart.execute(thinClient)

            verify {
                Dialogs.error(
                    "The restart request carries no devfile template. The workspace was not restarted.",
                    "Restart Workspace"
                )
            }
            verify(exactly = 0) { thinClient.close() }
        }
    }

    @Test
    fun `#execute shows a restart error after reconnect when applying the template fails`() = runTest {
        // given
        every { workspaces.get(namespace, workspaceName) } returns
            workspace(mapOf(DevWorkspace.PENDING_TEMPLATE_KEY to templatePayload))
        every { workspaces.setSpecTemplate(any(), any(), any()) } throws ApiException("apply failed")

        // when/then
        runExecuteWithDialogsMock {
            restart.execute(thinClient)

            verify { workspaces.startAndWait(namespace, workspaceName, any()) }
            verify { Dialogs.error(any(), "Restart Workspace") }
        }
    }

    @Test
    fun `#execute removes the pending devfile template`() = runTest {
        // given
        every { workspaces.hasRestartAnnotation(namespace, workspaceName) } returns true

        // when
        runExecuteWithProgressMock {
            restart.execute(thinClient)
        }

        // then
        verify { workspaces.removePendingTemplate(namespace, workspaceName) }
    }

    private fun workspace(annotations: Map<String, String> = emptyMap()): DevWorkspace {
        return DevWorkspace.from(
            mapOf(
                "metadata" to mapOf(
                    "name" to workspaceName,
                    "namespace" to namespace,
                    "uid" to "test-uid",
                    "annotations" to annotations
                ),
                "spec" to mapOf("started" to true),
                "status" to mapOf("phase" to DevWorkspace.RUNNING)
            )
        )
    }

    private val templatePayload =
        """{"version":1,"template":{"components":[{"name":"tools"}]}}"""

    private suspend fun runExecuteWithDialogsMock(body: suspend () -> Unit) {
        mockkStatic(ApplicationManager::class)
        mockkStatic(Logger::class)
        mockkObject(Dialogs)
        try {
            val application = mockk<Application>(relaxed = true)
            every { ApplicationManager.getApplication() } returns application
            every { application.invokeLater(any<Runnable>()) } answers {
                firstArg<Runnable>().run()
            }
            every { Logger.getInstance(any<Class<*>>()) } returns mockk(relaxed = true)
            every { Dialogs.error(any(), any()) } just Runs
            runExecuteWithProgressMock(body)
        } finally {
            unmockkObject(Dialogs)
            unmockkStatic(Logger::class)
            unmockkStatic(ApplicationManager::class)
        }
    }

    private suspend fun runExecuteWithProgressMock(body: suspend () -> Unit) {
        mockkStatic(ProgressManager::class)
        try {
            val pm = mockk<ProgressManager>()
            every { ProgressManager.getInstance() } returns pm
            every { pm.progressIndicator } returns indicator
            every { pm.run(any<com.intellij.openapi.progress.Task.Backgroundable>()) } answers {
                firstArg<com.intellij.openapi.progress.Task.Backgroundable>()
                    .run(indicator)
            }
            body()
        } finally {
            unmockkStatic(ProgressManager::class)
        }
    }
}
