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
package com.redhat.devtools.gateway

import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.redhat.devtools.gateway.download.DevSpacesClientMirror
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.devworkspace.DevWorkspaces
import com.redhat.devtools.gateway.openshift.PortForwardSession
import com.redhat.devtools.gateway.server.RemoteIDEServer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class DevSpacesConnectionReconnectTest {

    private lateinit var devSpacesContext: DevSpacesContext
    private lateinit var connection: DevSpacesConnection
    private lateinit var forwarder: PortForwardSession
    private lateinit var thinClient: ThinClientHandle
    private lateinit var remoteIdeServer: RemoteIDEServer
    private lateinit var workspace: DevWorkspace

    @BeforeEach
    fun beforeEach() {
        workspace = mockk(relaxed = true) {
            every { name } returns "test-workspace"
            every { namespace } returns "test-namespace"
            every { annotations } returns emptyMap()
        }
        devSpacesContext = mockk(relaxed = true) {
            every { devWorkspace } returns workspace
        }
        forwarder = mockk(relaxed = true)
        thinClient = mockk(relaxed = true)
        remoteIdeServer = mockk(relaxed = true)
        connection = DevSpacesConnection(devSpacesContext)

        mockkConstructor(DevWorkspaces::class)
        every { anyConstructed<DevWorkspaces>().get(any(), any()) } returns workspace
    }

    @AfterEach
    fun afterEach() {
        unmockkConstructor(DevWorkspaces::class)
    }

    @Test
    fun `#onThinClientClosed ignores a close issued by the reconnect itself`() {
        val sessionCtx = session(reconnecting = true)
        sessionCtx.handlesClosedByReconnect.add(thinClient)

        connection.onThinClientClosed(thinClient, workspace, sessionCtx)

        assertThat(sessionCtx.connectFailed.get()).isFalse()
        assertThat(sessionCtx.handlesClosedByReconnect).isEmpty()
        verify(exactly = 0) { devSpacesContext.removeWorkspace(any()) }
        verify(exactly = 0) { forwarder.close() }
    }

    @Test
    fun `#onThinClientClosed ignores repeated close events for a handle the reconnect closed`() {
        val sessionCtx = session(reconnecting = true)
        // the reconnect demoted the handle when it closed it: it is no longer the session's client
        sessionCtx.thinClient = null
        sessionCtx.handlesClosedByReconnect.add(thinClient)

        connection.onThinClientClosed(thinClient, workspace, sessionCtx)
        connection.onThinClientClosed(thinClient, workspace, sessionCtx)

        assertThat(sessionCtx.connectFailed.get()).isFalse()
        assertThat(sessionCtx.handlesClosedByReconnect).isEmpty()
        verify(exactly = 0) { devSpacesContext.removeWorkspace(any()) }
        verify(exactly = 0) { forwarder.close() }
    }

    @Test
    fun `#onThinClientClosed aborts the reconnect and tears down when the user closes during reconnect`() {
        var reconnectCanceled = false
        val sessionCtx = session(reconnecting = true)
        sessionCtx.cancelReconnect = { reconnectCanceled = true }

        connection.onThinClientClosed(thinClient, workspace, sessionCtx)

        assertThat(sessionCtx.connectFailed.get()).isTrue()
        assertThat(reconnectCanceled).isTrue()
        verify(exactly = 1) { devSpacesContext.removeWorkspace(workspace) }
        verify(timeout = 2_000) { forwarder.close() }
    }

    @Test
    fun `#onThinClientClosed tears down after reconnecting cleared`() {
        connection.onThinClientClosed(thinClient, workspace, session(reconnecting = false))

        verify(exactly = 1) { devSpacesContext.removeWorkspace(workspace) }
        verify(timeout = 2_000) { forwarder.close() }
    }

    @Test
    fun `#startThinClientOnReconnect starts the replacement client without the wizard onConnected`() = runTest {
        val startedCallbacks = mutableListOf<() -> Unit>()
        var onConnectedInvoked = false
        val sessionCtx = session(reconnecting = false, onConnected = { onConnectedInvoked = true })
        every { thinClient.clientPresent } returns true

        mockkConstructor(DevSpacesClientMirror::class)
        try {
            coEvery {
                anyConstructed<DevSpacesClientMirror>().ensureMirrored(any(), any(), any())
            } returns AutoCloseable { }

            withMockedLinkedClientManager(startedCallbacks) {
                connection.startThinClientOnReconnect(
                    "tcp://localhost:5990#cb=261.22158.277&fp=NEW",
                    sessionCtx,
                    workspace,
                )
            }
        } finally {
            unmockkConstructor(DevSpacesClientMirror::class)
        }

        assertThat(startedCallbacks).hasSize(1)
        assertThatObject(startedCallbacks.single()).isNotSameAs(sessionCtx.onConnected)
        startedCallbacks.single().invoke()
        assertThat(onConnectedInvoked).isFalse()
    }

    @Test
    fun `#startThinClientOnReconnect mirrors the client before starting so the platform does not re-download`() =
        runTest {
            val startedCallbacks = mutableListOf<() -> Unit>()
            val sessionCtx = session(reconnecting = true)
            every { thinClient.clientPresent } returns true
            var mirroredJoinLink: URI? = null

            mockkConstructor(DevSpacesClientMirror::class)
            try {
                coEvery {
                    anyConstructed<DevSpacesClientMirror>().ensureMirrored(any(), any(), any())
                } answers {
                    mirroredJoinLink = invocation.args[0] as URI
                    AutoCloseable { }
                }

                withMockedLinkedClientManager(startedCallbacks) {
                    connection.startThinClientOnReconnect(
                        "tcp://localhost:5990#cb=261.22158.277&fp=NEW",
                        sessionCtx,
                        workspace,
                    )
                }
            } finally {
                unmockkConstructor(DevSpacesClientMirror::class)
            }

            assertThat(mirroredJoinLink).isNotNull
            assertThat(mirroredJoinLink!!.fragment).contains("cb=261.22158.277")
            assertThat(startedCallbacks).hasSize(1)
        }

    @Test
    fun `#startThinClient starts the initial client with the wizard onConnected`() = runTest {
        val startedCallbacks = mutableListOf<() -> Unit>()
        var onConnectedInvoked = false
        val sessionCtx = session(reconnecting = false, onConnected = { onConnectedInvoked = true })

        withMockedLinkedClientManager(startedCallbacks) {
            connection.startThinClient("tcp://localhost:5990?fp=NEW", sessionCtx, workspace)
        }

        assertThat(startedCallbacks).hasSize(1)
        assertThatObject(startedCallbacks.single()).isSameAs(sessionCtx.onConnected)
        startedCallbacks.single().invoke()
        assertThat(onConnectedInvoked).isTrue()
    }

    private fun session(
        reconnecting: Boolean,
        onConnected: () -> Unit = {},
    ): ThinClientSessionContext =
        ThinClientSessionContext(
            localPort = 12_345,
            remoteIdeServer = remoteIdeServer,
            onConnected = onConnected,
            onDisconnected = {},
            onDevWorkspaceStopped = {},
        ).apply {
            forwarder = this@DevSpacesConnectionReconnectTest.forwarder
            connectionLive.set(true)
            this.reconnecting.set(reconnecting)
            // the closed client is the session's current client unless the reconnect replaced it
            thinClient = this@DevSpacesConnectionReconnectTest.thinClient
        }

    /**
     * Runs [body] with [LinkedClientManager.getInstance] mocked, recording the client-started
     * callbacks handed to `startNewClient`.
     */
    private suspend fun withMockedLinkedClientManager(
        startedCallbacks: MutableList<() -> Unit>,
        body: suspend () -> Unit,
    ) {
        val manager = mockk<LinkedClientManager>()
        mockkObject(LinkedClientManager.Companion)
        try {
            every { LinkedClientManager.getInstance() } returns manager
            every {
                manager.startNewClient(
                    any<Lifetime>(), any<URI>(), any<String>(), any<() -> Unit>(), any<Boolean>()
                )
            } answers {
                startedCallbacks.add(arg(3))
                thinClient
            }
            body()
        } finally {
            unmockkObject(LinkedClientManager.Companion)
        }
    }
}
