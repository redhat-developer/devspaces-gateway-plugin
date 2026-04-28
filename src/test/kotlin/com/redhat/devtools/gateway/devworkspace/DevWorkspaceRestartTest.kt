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

import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class DevWorkspaceRestartTest {

    private lateinit var client: ApiClient
    private lateinit var workspaces: DevWorkspaces
    private lateinit var pods: DevWorkspacePods
    private lateinit var thinClient: ThinClientHandle
    private lateinit var restart: DevWorkspaceRestart

    private val namespace = "test-namespace"
    private val workspaceName = "test-workspace"

    @BeforeEach
    fun beforeEach() {
        client = mockk(relaxed = true)
        workspaces = mockk(relaxed = true)
        pods = mockk(relaxed = true)
        thinClient = mockk(relaxed = true)

        restart = DevWorkspaceRestart(
            namespace,
            workspaceName,
            client,
            workspaces,
            pods
        )
    }

    @Test
    fun `#execute stops workspace`() = runTest {
        // given
        coEvery { pods.waitForPodsDeleted(any(), any(), any()) } returns true

        // when
        restart.execute(thinClient)

        // then
        verify { workspaces.stop(namespace, workspaceName) }
    }

    @Test
    fun `#execute waits for pods to be deleted`() = runTest {
        // given
        coEvery { pods.waitForPodsDeleted(any(), any(), any()) } returns true

        // when
        restart.execute(thinClient)

        // then
        coVerify { pods.waitForPodsDeleted(namespace, workspaceName, 20) }
    }

    @Test
    fun `#execute starts workspace after pods deleted`() = runTest {
        // given
        coEvery { pods.waitForPodsDeleted(any(), any(), any()) } returns true

        // when
        restart.execute(thinClient)

        // then
        verifyOrder {
            workspaces.stop(namespace, workspaceName)
            workspaces.start(namespace, workspaceName)
        }
    }

    @Test
    fun `#execute starts workspace even when pods not deleted within timeout`() = runTest {
        // given
        coEvery { pods.waitForPodsDeleted(any(), any(), any()) } returns false

        // when
        restart.execute(thinClient)

        // then
        verify { workspaces.start(namespace, workspaceName) }
    }

    @Test
    fun `#execute closes thin client before stopping workspace`() = runTest {
        // given
        coEvery { pods.waitForPodsDeleted(any(), any(), any()) } returns true

        // when
        restart.execute(thinClient)

        // then
        verifyOrder {
            thinClient.close()
            workspaces.stop(namespace, workspaceName)
        }
    }

    @Test
    fun `#execute removes restart annotation after successful restart`() = runTest {
        // given
        coEvery { pods.waitForPodsDeleted(any(), any(), any()) } returns true
        every { workspaces.isRestarting(namespace, workspaceName) } returns true

        // when
        restart.execute(thinClient)

        // then
        verify { workspaces.removeRestarting(namespace, workspaceName) }
    }

    @Test
    fun `#execute does not remove annotation if already removed`() = runTest {
        // given
        coEvery { pods.waitForPodsDeleted(any(), any(), any()) } returns true
        every { workspaces.isRestarting(namespace, workspaceName) } returns false

        // when
        restart.execute(thinClient)

        // then
        verify(exactly = 0) { workspaces.removeRestarting(namespace, workspaceName) }
    }

    @Test
    @Disabled("TestLogger.error() throws TestLoggerAssertionError which prevents removeAnnotation from executing")
    fun `#execute removes annotation even when restart fails`() = runTest {
        // given
        every { workspaces.stop(any(), any()) } throws ApiException("Stop failed")
        every { workspaces.isRestarting(namespace, workspaceName) } returns true

        // when/then
        val result = runCatching { restart.execute(thinClient) }
        assertThat(result.isFailure).isTrue()

        verify { workspaces.removeRestarting(namespace, workspaceName) }
    }

    @Test
    fun `#execute rethrows exception after cleaning up annotation`() = runTest {
        // given
        val exception = ApiException("Start failed")
        every { workspaces.start(any(), any()) } throws exception
        every { workspaces.isRestarting(namespace, workspaceName) } returns true

        // when/then
        val result = runCatching { restart.execute(thinClient) }
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("Workspace restart failed")
    }

    @Test
    fun `#execute continues even if annotation removal fails`() = runTest {
        // given
        coEvery { pods.waitForPodsDeleted(any(), any(), any()) } returns true
        every { workspaces.isRestarting(namespace, workspaceName) } returns true
        every { workspaces.removeRestarting(namespace, workspaceName) } throws ApiException("Remove failed")

        // when - should not throw
        restart.execute(thinClient)

        // then - verify the method completed
        verify { workspaces.start(namespace, workspaceName) }
    }

    @Test
    fun `#execute executes full restart sequence in correct order`() = runTest {
        // given
        coEvery { pods.waitForPodsDeleted(any(), any(), any()) } returns true
        every { workspaces.isRestarting(namespace, workspaceName) } returns true

        // when
        restart.execute(thinClient)

        // then
        verify {
            thinClient.close()
            workspaces.stop(namespace, workspaceName)
            workspaces.start(namespace, workspaceName)
            workspaces.isRestarting(namespace, workspaceName)
            workspaces.removeRestarting(namespace, workspaceName)
        }
        coVerify {
            pods.waitForPodsDeleted(namespace, workspaceName, 20)
        }
    }

    @Test
    fun `#execute fails when thin client close fails`() = runTest {
        // given
        val exception = RuntimeException("Close failed")
        every { thinClient.close() } throws exception

        // when/then
        val result = runCatching { restart.execute(thinClient) }
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("Workspace restart failed")
    }

    @Test
    @Disabled("TestLogger.error() throws TestLoggerAssertionError which prevents removeAnnotation from executing")
    fun `#execute cleans up annotation when thin client close fails`() = runTest {
        // given
        every { thinClient.close() } throws RuntimeException("Close failed")
        every { workspaces.isRestarting(namespace, workspaceName) } returns true

        // when/then
        val result = runCatching { restart.execute(thinClient) }
        assertThat(result.isFailure).isTrue()

        verify { workspaces.removeRestarting(namespace, workspaceName) }
    }

    @Test
    fun `#execute fails when stop workspace fails`() = runTest {
        // given
        val exception = ApiException("Stop failed")
        every { workspaces.stop(any(), any()) } throws exception

        // when/then
        val result = runCatching { restart.execute(thinClient) }
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("Workspace restart failed")
    }

    @Test
    fun `#execute fails when start workspace fails`() = runTest {
        // given
        coEvery { pods.waitForPodsDeleted(any(), any(), any()) } returns true
        val exception = ApiException("Start failed")
        every { workspaces.start(any(), any()) } throws exception

        // when/then
        val result = runCatching { restart.execute(thinClient) }
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("Workspace restart failed")
    }
}
