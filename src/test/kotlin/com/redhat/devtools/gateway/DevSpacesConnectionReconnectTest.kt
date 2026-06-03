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
package com.redhat.devtools.gateway

import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.devworkspace.DevWorkspaces
import com.redhat.devtools.gateway.server.RemoteIDEServer
import io.kubernetes.client.openapi.ApiClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class DevSpacesConnectionReconnectTest {

    private lateinit var devSpacesContext: DevSpacesContext
    private lateinit var connection: DevSpacesConnection
    private lateinit var forwarder: Closeable
    private lateinit var apiClient: ApiClient

    @BeforeEach
    fun beforeEach() {
        val devWorkspace = mockk<DevWorkspace>(relaxed = true) {
            every { name } returns "test-workspace"
            every { namespace } returns "test-namespace"
            every { annotations } returns emptyMap()
        }
        apiClient = mockk(relaxed = true)
        devSpacesContext = mockk(relaxed = true) {
            every { this@mockk.devWorkspace } returns devWorkspace
            every { client } returns apiClient
        }
        forwarder = mockk(relaxed = true)
        connection = DevSpacesConnection(devSpacesContext)
        mockkConstructor(DevWorkspaces::class)
        every { anyConstructed<DevWorkspaces>().get(any(), any()) } returns devWorkspace
    }

    @AfterEach
    fun afterEach() {
        unmockkConstructor(DevWorkspaces::class)
    }

    @Test
    fun `#onClientClosed does not tear down session when reconnecting`() {
        runBlocking {
            connection.onClientClosed(client = null, session = session(reconnecting = true))

            delay(200)

            verify(exactly = 0) { devSpacesContext.removeWorkspace(any()) }
            verify(exactly = 0) { forwarder.close() }
        }
    }

    @Test
    fun `#onClientClosed tears down session when not reconnecting`() {
        runBlocking {
            val teardownDone = CompletableDeferred<Unit>()
            val remoteIdeServer = mockk<RemoteIDEServer>(relaxed = true) {
                coEvery { waitServerTerminated() } returns false
            }
            val session = ThinClientSessionContext(
                localPort = 12_345,
                remoteIdeServer = remoteIdeServer,
                forwarder = forwarder,
                onConnected = {},
                onDisconnected = { teardownDone.complete(Unit) },
                onDevWorkspaceStopped = {},
                checkCancelled = null,
                reconnecting = AtomicBoolean(false),
            )

            connection.onClientClosed(client = null, session = session)

            withTimeout(2.seconds) {
                teardownDone.await()
            }

            verify(exactly = 1) { devSpacesContext.removeWorkspace(any()) }
            verify(exactly = 1) { forwarder.close() }
        }
    }

    private fun session(reconnecting: Boolean): ThinClientSessionContext =
        ThinClientSessionContext(
            localPort = 12_345,
            remoteIdeServer = mockk<RemoteIDEServer>(relaxed = true),
            forwarder = forwarder,
            onConnected = {},
            onDisconnected = {},
            onDevWorkspaceStopped = {},
            checkCancelled = null,
            reconnecting = AtomicBoolean(reconnecting),
        )
}
