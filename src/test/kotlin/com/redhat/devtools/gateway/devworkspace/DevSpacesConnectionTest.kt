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

import com.intellij.openapi.project.ex.ProjectManagerEx
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.redhat.devtools.gateway.ClientWindowFocus
import com.redhat.devtools.gateway.ThinClientDownloadException
import com.redhat.devtools.gateway.ThinClientLaunchException
import com.redhat.devtools.gateway.ThinClientRecoveryWatcher
import com.redhat.devtools.gateway.DevSpacesConnection
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.download.DevSpacesClientConnectProgress
import com.redhat.devtools.gateway.openshift.ForwardDials
import com.redhat.devtools.gateway.openshift.PortForwardSession
import com.redhat.devtools.gateway.openshift.PortForwarder
import com.redhat.devtools.gateway.server.RemoteIDEServer
import io.kubernetes.client.openapi.models.V1Pod
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** A forwarder that yields [session] and reports every probe as usable. */
private fun fixedPortForwarder(session: PortForwardSession): PortForwarder =
    object : PortForwarder {
        override fun forward(
            pod: V1Pod,
            remotePort: Int,
            onRemoteEnd: () -> Unit
        ): PortForwardSession = session

        override suspend fun probe(pod: V1Pod, remotePort: Int): Boolean = true
    }

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

        connection = DevSpacesConnection(
            devSpacesContext,
            ClientWindowFocus()
        )

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

    @Test
    fun `teardown closes the port forward`() {
        val closed = AtomicBoolean(false)
        val session = PortForwardSession(41_234) { closed.set(true) }
        val connectionWithForward = DevSpacesConnection(
            devSpacesContext,
            ClientWindowFocus(),
            fixedPortForwarder(session)
        )

        connectionWithForward.onThinClientClosed(
            AtomicBoolean(false),
            AtomicBoolean(true),
            thinClient,
            devSpacesContext.devWorkspace,
            {},
            {},
            null,
            session
        )

        // teardown runs on Dispatchers.IO: wait for it to reach the forward
        runBlocking {
            withTimeout(2_000) {
                while (!closed.get()) {
                    delay(10)
                }
            }
        }
        assertThat(closed).isTrue()
    }

    @Test
    fun `teardown during restart keeps host projects and workspace`() {
        val closed = AtomicBoolean(false)
        val session = PortForwardSession(41_234) { closed.set(true) }
        val connectionWithForward = DevSpacesConnection(
            devSpacesContext,
            ClientWindowFocus(),
            fixedPortForwarder(session)
        )
        every { anyConstructed<DevWorkspaces>().get(any<String>(), any<String>()) } returns
            mockk(relaxed = true) {
                every { annotations } returns mapOf(
                    DevWorkspace.RESTART_KEY to DevWorkspace.RESTART_VALUE
                )
            }
        mockkObject(ProjectManagerEx.Companion)
        try {
            every { ProjectManagerEx.Companion.getInstanceEx() } returns mockk(relaxed = true)
            connectionWithForward.onThinClientClosed(
                AtomicBoolean(false),
                AtomicBoolean(true),
                thinClient,
                devSpacesContext.devWorkspace,
                {},
                {},
                mockk(relaxed = true),
                session
            )

            // teardown runs on Dispatchers.IO: wait for it to reach the forward
            runBlocking {
                withTimeout(2_000) {
                    while (!closed.get()) {
                        delay(10)
                    }
                }
            }

            verify(exactly = 0) { ProjectManagerEx.Companion.getInstanceEx() }
            verify(exactly = 0) { anyConstructed<DevWorkspaces>().stop(any(), any()) }
        } finally {
            unmockkObject(ProjectManagerEx.Companion)
        }
    }

    @Test
    fun `connect resets the focus throttle`() {
        // given - the previous session already used up the focus interval
        val clientWindowFocus = ClientWindowFocus()
        val reusingConnection = DevSpacesConnection(devSpacesContext, clientWindowFocus)
        clientWindowFocus.requestFocus(thinClient)

        // when - a reconnect starts and is cancelled before it reaches the client
        assertThrows<CancellationException> {
            runBlocking {
                reusingConnection.connect(
                    onConnected = {},
                    onConnectionEnded = {},
                    onDevWorkspaceStopped = {},
                    checkCancelled = { throw CancellationException("cancelled") }
                )
            }
        }

        // then - the new session may focus at once instead of waiting out the old throttle
        clientWindowFocus.requestFocus(thinClient)
        verify(exactly = 2) { thinClient.focusClientWindow() }
    }

    @Test
    fun `teardown during active recovery withholds the workspace stop`() {
        // given - the Guest is closed while recovery is still polling and the IDE looks terminated
        val forwardClosed = AtomicBoolean(false)
        val session = PortForwardSession(41_234) { forwardClosed.set(true) }
        val connectionWithForward = DevSpacesConnection(
            devSpacesContext,
            ClientWindowFocus(),
            fixedPortForwarder(session)
        )
        val watcher = activeRecovery()
        connectionWithForward.installRecoveryWatcher(watcher)
        every { anyConstructed<DevWorkspaces>().stop(any(), any()) } just Runs

        // when - the workspace may be merely unreachable, so the stop decision is not ours (§7.9)
        connectionWithForward.onThinClientClosed(
            AtomicBoolean(false),
            AtomicBoolean(true),
            thinClient,
            devSpacesContext.devWorkspace,
            {},
            {},
            terminatingServer(),
            session
        )
        awaitForwardClosed(forwardClosed)

        // then - the teardown ran in full, the workspace was not stopped, recovery is cancelled
        assertThat(forwardClosed).isTrue()
        assertThat(watcher.isActive).isFalse()
        verify(exactly = 0) { anyConstructed<DevWorkspaces>().stop(any(), any()) }
    }

    @Test
    fun `teardown stops the workspace when recovery already ended`() {
        // given - recovery gave up (or succeeded) before the Guest was closed
        val forwardClosed = AtomicBoolean(false)
        val session = PortForwardSession(41_234) { forwardClosed.set(true) }
        val connectionWithForward = DevSpacesConnection(
            devSpacesContext,
            ClientWindowFocus(),
            fixedPortForwarder(session)
        )
        val watcher = activeRecovery()
        watcher.stop()
        connectionWithForward.installRecoveryWatcher(watcher)
        every { anyConstructed<DevWorkspaces>().stop(any(), any()) } just Runs

        // when - the Guest is closed, the healthy-path close => stop contract is unchanged
        connectionWithForward.onThinClientClosed(
            AtomicBoolean(false),
            AtomicBoolean(true),
            thinClient,
            devSpacesContext.devWorkspace,
            {},
            {},
            terminatingServer(),
            session
        )
        awaitForwardClosed(forwardClosed)

        // then
        verify(exactly = 1) { anyConstructed<DevWorkspaces>().stop(any(), any()) }
    }

    /** Teardown runs on Dispatchers.IO: wait until it closed the forward, its last step. */
    private fun awaitForwardClosed(closed: AtomicBoolean) {
        runBlocking {
            withTimeout(2_000) {
                while (!closed.get()) {
                    delay(10)
                }
            }
        }
    }

    /** A running recovery: no pod is ever resolved, so it keeps polling until it is stopped. */
    private fun activeRecovery(): ThinClientRecoveryWatcher {
        val watcher = ThinClientRecoveryWatcher(
            ThinClientRecoveryWatcher.Collaborators(
                resolvePod = { null },
                probe = { true },
                cachedLink = URI("tcp://127.0.0.1:41500#cb=1"),
                refreshLink = { null },
                prod = {},
                session = idleDials(),
                isClientPresent = { true },
                onRecovered = {},
                onRecoveryFailed = {}
            ),
            ThinClientRecoveryWatcher.Timing(
                pollIntervalMs = 60_000,
                budgetMs = 600_000
            )
        )
        watcher.start(CoroutineScope(Dispatchers.Default))
        return watcher
    }

    private fun idleDials(): ForwardDials = object : ForwardDials {
        override val hasActiveForward: Boolean = false
        override val lastSuccessfulHandshakeNanos: Long? = null
        override val lastFastFailedDialNanos: Long? = null
        override val lastLongLivedDialNanos: Long? = null
        override val fastFailedDials: Long = 0
    }

    /** An IDE server that reports "terminated": without the guard, teardown would stop the workspace. */
    private fun terminatingServer(): RemoteIDEServer = mockk(relaxed = true) {
        coEvery { waitServerTerminated(any()) } returns true
    }
}
