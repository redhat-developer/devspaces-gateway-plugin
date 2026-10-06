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
import com.redhat.devtools.gateway.ThinClientNotReadyException
import com.redhat.devtools.gateway.DevSpacesConnection
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.devworkspace.DevWorkspaces
import io.mockk.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class DevSpacesConnectionTest {

    private lateinit var devSpacesContext: DevSpacesContext
    private lateinit var thinClient: ThinClientHandle

    private val namespace = "test-namespace"
    private val workspaceName = "test-workspace"

    private lateinit var connection: DevSpacesConnection

    @BeforeEach
    fun beforeEach() {
        devSpacesContext = mockk(relaxed = true) {
            every { devWorkspace.namespace } returns namespace
            every { devWorkspace.name } returns workspaceName
        }

        thinClient = mockk(relaxed = true) {
            every { clientPresent } returns false
            every { lifetime } returns mockk(relaxed = true)
        }

        connection = DevSpacesConnection(devSpacesContext)

        mockkConstructor(DevWorkspaces::class)
        every {
            anyConstructed<DevWorkspaces>().get(any<String>(), any<String>())
        } returns mockk(relaxed = true) {
            every { annotations } returns emptyMap()
        }
    }

    @Test
    fun `waitForThinClientConnect succeeds when client is present`() = runTest {
        val connectFailed = AtomicBoolean(false)
        every { thinClient.clientPresent } returns true

        connection.waitForThinClientConnect(thinClient, connectFailed, null)
    }

    @Test
    fun `waitForThinClientConnect does not time out while client is absent`() =
        runTest {
            val connectFailed = AtomicBoolean(false)
            every { thinClient.clientPresent } returns false

            val job: Job = launch {
                connection.waitForThinClientConnect(thinClient, connectFailed, null)
            }

            yield()

            assertThat(job.isActive).isTrue()

            job.cancel()
        }

    @Test
    fun `waitForThinClientConnect tolerates transient presence absence`() = runTest {
        val connectFailed = AtomicBoolean(false)
        var callCount = 0

        every { thinClient.clientPresent } answers {
            callCount++
            callCount > 3
        }

        connection.waitForThinClientConnect(thinClient, connectFailed, null)

        assertThat(callCount).isGreaterThan(3)
    }

    @Test
    fun `waitForThinClientConnect fails when connectFailed is set`() = runTest {
        val connectFailed = AtomicBoolean(true)
        every { thinClient.clientPresent } returns false

        var thrown: Throwable? = null
        try {
            connection.waitForThinClientConnect(thinClient, connectFailed, null)
        } catch (e: Throwable) {
            thrown = e
        }

        assertThat(thrown).isInstanceOf(ThinClientNotReadyException::class.java)
        assertThat(thrown?.message).contains("Could not connect")
    }

    @Test
    fun `onThinClientClosed sets connectFailed and tears down when live`() {
        val connectFailed = AtomicBoolean(false)
        val connectionLive = AtomicBoolean(true)

        connection.onThinClientClosed(
            connectFailed,
            connectionLive,
            thinClient,
            devSpacesContext.devWorkspace,
            mockk<() -> Unit>(relaxed = true),
            {},
            null,
            null
        )

        assertThat(connectFailed.get()).isTrue()

        verify {
            devSpacesContext.removeWorkspace(devSpacesContext.devWorkspace)
        }
    }

    @Test
    fun `onThinClientClosed does not tear down when not live`() {
        val connectFailed = AtomicBoolean(false)
        val connectionLive = AtomicBoolean(false)

        connection.onThinClientClosed(
            connectFailed,
            connectionLive,
            thinClient,
            devSpacesContext.devWorkspace,
            mockk<() -> Unit>(relaxed = true),
            {},
            null,
            null
        )

        assertThat(connectFailed.get()).isTrue()

        verify(exactly = 0) {
            devSpacesContext.removeWorkspace(any())
        }
    }
}