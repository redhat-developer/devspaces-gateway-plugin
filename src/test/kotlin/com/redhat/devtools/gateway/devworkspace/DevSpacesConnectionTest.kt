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
import com.redhat.devtools.gateway.ThinClientDownloadException
import com.redhat.devtools.gateway.ThinClientLaunchException
import com.redhat.devtools.gateway.DevSpacesConnection
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.ThinClientSessionContext
import com.redhat.devtools.gateway.download.DevSpacesClientConnectProgress
import io.mockk.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

        // these tests run without a platform application: run the focus request in place
        connection = DevSpacesConnection(devSpacesContext) { action -> action() }

        mockkConstructor(DevWorkspaces::class)
        every {
            anyConstructed<DevWorkspaces>().get(any<String>(), any<String>())
        } returns mockk(relaxed = true) {
            every { annotations } returns emptyMap()
        }
    }

    @Test
    fun `waitForConnect succeeds when client is present`() = runTest {
        val connectFailed = AtomicBoolean(false)
        every { thinClient.clientPresent } returns true

        connection.waitForConnect(thinClient, connectFailed, null)
    }

    @Test
    fun `waitForConnect does not time out while client is absent`() =
        runTest {
            val connectFailed = AtomicBoolean(false)
            every { thinClient.clientPresent } returns false

            val job: Job = launch {
                connection.waitForConnect(thinClient, connectFailed, null)
            }

            yield()

            assertThat(job.isActive).isTrue()

            job.cancel()
        }

    @Test
    fun `waitForConnect tolerates transient presence absence`() = runTest {
        val connectFailed = AtomicBoolean(false)
        var callCount = 0

        every { thinClient.clientPresent } answers {
            callCount++
            callCount > 3
        }

        connection.waitForConnect(thinClient, connectFailed, null)

        assertThat(callCount).isGreaterThan(3)
    }

    @Test
    fun `waitForConnect fails when connectFailed is set`() = runTest {
        val connectFailed = AtomicBoolean(true)
        every { thinClient.clientPresent } returns false

        val thrown = assertThrows<ThinClientLaunchException> {
            connection.waitForConnect(thinClient, connectFailed, null)
        }

        assertThat(thrown.message).contains("Could not start the JetBrains Client")
    }

    @Test
    fun `mirrorClient reports download failures as download exceptions`() = runTest {
        val thrown = assertThrows<ThinClientDownloadException> {
            connection.mirrorClient(
                URI("tcp://127.0.0.1:5990#cb=not-a-build-number"),
                DevSpacesClientConnectProgress(),
                null,
            )
        }

        assertThat(thrown.message).contains("Could not download the JetBrains Client")
        assertThat(thrown.message).contains("Invalid client build number")
    }

    @Test
    fun `onThinClientClosed sets connectFailed and tears down when live`() {
        val sessionCtx = session(connectionLive = true)

        connection.onThinClientClosed(
            thinClient,
            devSpacesContext.devWorkspace,
            sessionCtx
        )

        assertThat(sessionCtx.connectFailed.get()).isTrue()

        verify {
            devSpacesContext.removeWorkspace(devSpacesContext.devWorkspace)
        }
    }

    @Test
    fun `onThinClientClosed does not tear down when not live`() {
        val sessionCtx = session(connectionLive = false)

        connection.onThinClientClosed(
            thinClient,
            devSpacesContext.devWorkspace,
            sessionCtx
        )

        assertThat(sessionCtx.connectFailed.get()).isTrue()

        verify(exactly = 0) {
            devSpacesContext.removeWorkspace(any())
        }
    }

    @Test
    fun `focusClientWindow requests the Guest window focus`() {
        connection.focusClientWindow(thinClient)

        verify { thinClient.focusClientWindow() }
    }

    @Test
    fun `focusClientWindow focuses at most once per interval`() {
        connection.focusClientWindow(thinClient)
        connection.focusClientWindow(thinClient)

        verify(exactly = 1) { thinClient.focusClientWindow() }
    }

    @Test
    fun `focusClientWindow ignores a client that can no longer be focused`() {
        every { thinClient.focusClientWindow() } throws IllegalStateException("client closed")

        connection.focusClientWindow(thinClient) // does not throw
    }

    @Test
    fun `focusClientWindow dispatches the focus request to the UI thread`() {
        val dispatches = AtomicInteger()
        val dispatchingConnection = DevSpacesConnection(devSpacesContext) { action ->
            dispatches.incrementAndGet()
            action()
        }

        dispatchingConnection.focusClientWindow(thinClient)

        assertThat(dispatches.get()).isEqualTo(1)
        verify(exactly = 1) { thinClient.focusClientWindow() }
    }

    @Test
    fun `connect resets the focus throttle`() {
        // given - the previous session already used up the focus interval
        connection.focusClientWindow(thinClient)

        // when - a reconnect starts and is cancelled before it reaches the client
        assertThrows<CancellationException> {
            runBlocking {
                connection.connect(
                    onConnected = {},
                    onConnectionEnded = {},
                    onDevWorkspaceStopped = {},
                    checkCancelled = { throw CancellationException("cancelled") }
                )
            }
        }

        // then - the new session may focus at once instead of waiting out the old throttle
        connection.focusClientWindow(thinClient)
        verify(exactly = 2) { thinClient.focusClientWindow() }
    }

    private fun session(connectionLive: Boolean): ThinClientSessionContext =
        ThinClientSessionContext(
            localPort = 12_345,
            remoteIdeServer = mockk(relaxed = true),
            onConnected = {},
            onDisconnected = {},
            onDevWorkspaceStopped = {},
        ).apply {
            this.connectionLive.set(connectionLive)
            // a genuinely closed client is the session's current client
            thinClient = this@DevSpacesConnectionTest.thinClient
        }
}
