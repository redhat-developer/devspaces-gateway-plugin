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
import com.redhat.devtools.gateway.ThinClientDownloadException
import com.redhat.devtools.gateway.ThinClientLaunchException
import com.redhat.devtools.gateway.recovery.ThinClientRecoveryWatcher
import com.redhat.devtools.gateway.ConnectAttempt
import com.redhat.devtools.gateway.DevSpacesConnection
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.download.DevSpacesClientConnectProgress
import com.redhat.devtools.gateway.openshift.ForwardDials
import com.redhat.devtools.gateway.openshift.PortForwardSession
import com.redhat.devtools.gateway.server.RemoteIDEServer
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Time a wrongly restarted episode needs to poll: its first iteration resolves a pod at once. */
private const val RESTART_SETTLE_MS = 200L

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
    fun `waitForConnect succeeds when client is present`() = runTest {
        every { thinClient.clientPresent } returns true
        val attempt = attempt(client = thinClient)

        connection.waitForConnect(attempt, null)
    }

    @Test
    fun `waitForConnect does not time out while client is absent`() =
        runTest {
            every { thinClient.clientPresent } returns false
            val attempt = attempt(client = thinClient)

            val job: Job = launch {
                connection.waitForConnect(attempt, null)
            }

            yield()

            assertThat(job.isActive).isTrue()

            job.cancel()
        }

    @Test
    fun `waitForConnect tolerates transient presence absence`() = runTest {
        var callCount = 0

        every { thinClient.clientPresent } answers {
            callCount++
            callCount > 3
        }
        val attempt = attempt(client = thinClient)

        connection.waitForConnect(attempt, null)

        assertThat(callCount).isGreaterThan(3)
    }

    @Test
    fun `waitForConnect fails when connectWaitEnded is set`() = runTest {
        every { thinClient.clientPresent } returns false
        val attempt = attempt(client = thinClient).apply { connectWaitEnded.set(true) }

        val thrown = assertThrows<ThinClientLaunchException> {
            connection.waitForConnect(attempt, null)
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
    fun `onThinClientClosed sets connectWaitEnded and tears down when live`() {
        val attempt = attempt(client = thinClient).apply { setSessionLive(true) }

        connection.onThinClientClosed(attempt)

        assertThat(attempt.connectWaitEnded.get()).isTrue()

        verify {
            devSpacesContext.removeWorkspace(devSpacesContext.devWorkspace)
        }
    }

    @Test
    fun `onThinClientClosed does not tear down when not live`() {
        val attempt = attempt(client = thinClient)

        connection.onThinClientClosed(attempt)

        assertThat(attempt.connectWaitEnded.get()).isTrue()

        verify(exactly = 0) {
            devSpacesContext.removeWorkspace(any())
        }
    }

    @Test
    fun `teardown closes the port forward`() {
        val (connectionWithForward, session, closed) = forwardingConnection()

        endLiveConnection(connectionWithForward, session = session)

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
        val (connectionWithForward, session, closed) = forwardingConnection()
        every { anyConstructed<DevWorkspaces>().get(any<String>(), any<String>()) } returns
            mockk(relaxed = true) {
                every { annotations } returns mapOf(
                    DevWorkspace.RESTART_KEY to DevWorkspace.RESTART_VALUE
                )
            }
        mockkObject(ProjectManagerEx.Companion)
        try {
            every { ProjectManagerEx.Companion.getInstanceEx() } returns mockk(relaxed = true)
            endLiveConnection(connectionWithForward, server = mockk(relaxed = true), session = session)

            // teardown runs on Dispatchers.IO: wait for it to reach the forward
            awaitForwardClosed(closed)

            verify(exactly = 0) { ProjectManagerEx.Companion.getInstanceEx() }
            verify(exactly = 0) { anyConstructed<DevWorkspaces>().stop(any(), any()) }
        } finally {
            unmockkObject(ProjectManagerEx.Companion)
        }
    }

    @Test
    fun `teardown during active recovery withholds the workspace stop`() {
        // given - the Guest is closed while recovery is still polling and the IDE looks terminated
        val (connectionWithForward, session, forwardClosed) = forwardingConnection()
        val watcher = activeRecovery()
        connectionWithForward.installRecoveryWatcher(watcher)
        every { anyConstructed<DevWorkspaces>().stop(any(), any()) } just Runs

        // when - the workspace may be merely unreachable, so the stop decision is not ours (§7.9)
        endLiveConnection(connectionWithForward, server = terminatingServer(), session = session)
        awaitForwardClosed(forwardClosed)

        // then - the teardown ran in full, the workspace was not stopped, recovery is cancelled
        assertThat(forwardClosed).isTrue()
        assertThat(watcher.isActive()).isFalse()
        verify(exactly = 0) { anyConstructed<DevWorkspaces>().stop(any(), any()) }
    }

    @Test
    fun `teardown stops the workspace when recovery already ended`() {
        // given - recovery gave up (or succeeded) before the Guest was closed
        val (connectionWithForward, session, forwardClosed) = forwardingConnection()
        val watcher = activeRecovery()
        watcher.stop()
        connectionWithForward.installRecoveryWatcher(watcher)
        every { anyConstructed<DevWorkspaces>().stop(any(), any()) } just Runs

        // when - the Guest is closed, the healthy-path close => stop contract is unchanged
        endLiveConnection(connectionWithForward, server = terminatingServer(), session = session)
        awaitForwardClosed(forwardClosed)

        // then
        verify(exactly = 1) { anyConstructed<DevWorkspaces>().stop(any(), any()) }
    }

    @Test
    fun `workspace stop cuts the connections but keeps recovery armed and listening`() {
        // given - a live session with an armed, idle recovery when the workspace is stopped
        val forwardClosed = AtomicBoolean(false)
        val clientsDropped = AtomicBoolean(false)
        val session = PortForwardSession(
            41_234,
            dropClients = { clientsDropped.set(true) }
        ) {
            forwardClosed.set(true)
        }
        val connectionWithForward = DevSpacesConnection(
            devSpacesContext,
            openPortForward = { _, _, _, _ -> session },
            probePortForward = { _, _ -> true }
        )
        val watcher = pollingRecovery(pollIntervalMs = 60_000, budgetMs = 600_000)
        connectionWithForward.installRecoveryWatcher(watcher, CoroutineScope(Dispatchers.Default))

        try {
            // when - the workspace stops: the loss must show without disarming recovery (CRW-13910)
            connectionWithForward.onWorkspaceStopped(session, thinClient)

            // then - the copied connections are cut, the listener stays, the loss is surfaced
            assertThat(clientsDropped).isTrue()
            assertThat(forwardClosed).isFalse()
            verify(exactly = 1) { thinClient.focusClientWindow() }

            // and - the connection loss the cut causes still starts the recovery episode: a
            // workspace started again is reconnected instead of staying "No connection". The
            // episode start focuses again - same window, one visible repaint
            connectionWithForward.onRemoteEnd(thinClient)
            assertThat(watcher.isActive()).isTrue()
            verify(exactly = 2) { thinClient.focusClientWindow() }
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun `repeated connection ends during one episode focus the window once`() {
        // given - a live connection: the episode latch is the only bound on focus, no time throttle
        val connectionWithFocus = DevSpacesConnection(devSpacesContext)
        val watcher = pollingRecovery(pollIntervalMs = 60_000, budgetMs = 600_000)
        connectionWithFocus.installRecoveryWatcher(watcher, CoroutineScope(Dispatchers.Default))

        try {
            // when - the Guest's dial churn ends connection after connection during the outage
            repeat(3) { connectionWithFocus.onRemoteEnd(thinClient) }
        } finally {
            watcher.stop()
        }

        // then - the loss became visible once instead of the window being pulled to the front for
        // the whole outage
        verify(exactly = 1) { thinClient.focusClientWindow() }
    }

    @Test
    fun `connection ends after a definitive failure neither restart recovery nor focus`() {
        // given - the budget is spent while the server stays dead
        val connectionWithFocus = DevSpacesConnection(devSpacesContext)
        val failed = AtomicInteger()
        val resolvedPods = AtomicInteger()
        val watcher = pollingRecovery(
            pollIntervalMs = 10,
            budgetMs = 50,
            resolvedPods = resolvedPods,
            onRecoveryFailed = { failed.incrementAndGet() }
        )
        connectionWithFocus.installRecoveryWatcher(watcher, CoroutineScope(Dispatchers.Default))

        connectionWithFocus.onRemoteEnd(thinClient)
        awaitCondition { failed.get() == 1 }
        assertThat(watcher.isActive()).isFalse()
        val podsBeforeFailure = resolvedPods.get()

        // when - the Guest's dial churn keeps ending connections after the failure was reported
        repeat(3) { connectionWithFocus.onRemoteEnd(thinClient) }

        // then - no episode restarts (a restarted loop polls at once) and the window is not stolen
        runBlocking { delay(RESTART_SETTLE_MS) }
        assertThat(resolvedPods.get()).isEqualTo(podsBeforeFailure)
        verify(exactly = 1) { thinClient.focusClientWindow() }
    }

    /** An attempt over [workspace]; callers set the state the test exercises. */
    private fun attempt(
        client: ThinClientHandle? = null,
        workspace: DevWorkspace = devSpacesContext.devWorkspace
    ): ConnectAttempt = ConnectAttempt(workspace, {}, {}).also { it.thinClient = client }

    /** Ends [connection] as live: a Guest close that reaches the teardown path. */
    private fun endLiveConnection(
        connection: DevSpacesConnection,
        server: RemoteIDEServer? = null,
        session: PortForwardSession? = null
    ) {
        val attempt = ConnectAttempt(devSpacesContext.devWorkspace, {}, {}).also {
            it.setSessionLive(true)
            it.thinClient = thinClient
            it.remoteIdeServer = server
            it.forwarder = session
        }
        connection.onThinClientClosed(attempt)
    }

    /** A connection over a fresh forward session, the session, and its closed flag. */
    private fun forwardingConnection(): Triple<DevSpacesConnection, PortForwardSession, AtomicBoolean> {
        val forwardClosed = AtomicBoolean(false)
        val session = PortForwardSession(41_234) { forwardClosed.set(true) }
        val connection = DevSpacesConnection(
            devSpacesContext,
            openPortForward = { _, _, _, _ -> session },
            probePortForward = { _, _ -> true }
        )
        return Triple(connection, session, forwardClosed)
    }

    /** Teardown runs on Dispatchers.IO: wait until it closed the forward, its last step. */
    private fun awaitForwardClosed(closed: AtomicBoolean) {
        awaitCondition { closed.get() }
    }

    /** Recovery and teardown run off the test thread: wait until [condition] holds. */
    private fun awaitCondition(condition: () -> Boolean) {
        runBlocking {
            withTimeout(2_000) {
                while (!condition()) {
                    delay(10)
                }
            }
        }
    }

    /**
     * A recovery that never resolves a pod, so it keeps polling until its budget is spent.
     *
     * @param resolvedPods counts the loop's pod lookups: a restarted loop polls again at once.
     * @param onRecoveryFailed counts the definitive failures the watcher reported.
     */
    private fun pollingRecovery(
        pollIntervalMs: Long,
        budgetMs: Long,
        resolvedPods: AtomicInteger = AtomicInteger(),
        onRecoveryFailed: () -> Unit = {}
    ): ThinClientRecoveryWatcher = ThinClientRecoveryWatcher(
        ThinClientRecoveryWatcher.RecoveryEnvironment(
            resolvePod = {
                resolvedPods.incrementAndGet()
                null
            },
            probe = { true },
            cachedLink = URI("tcp://127.0.0.1:41500#cb=1"),
            sessionPod = null,
            refreshLink = { null },
            reconnectInPlace = {},
            forceReconnect = {},
            session = idleDials(),
            isClientPresent = { true },
            onRecovered = {},
            onRecoveryFailed = onRecoveryFailed
        ),
        ThinClientRecoveryWatcher.Timing(
            pollIntervalMs = pollIntervalMs,
            budgetMs = budgetMs
        )
    )

    /** A running recovery: no pod is ever resolved, so it keeps polling until it is stopped. */
    private fun activeRecovery(): ThinClientRecoveryWatcher =
        pollingRecovery(pollIntervalMs = 60_000, budgetMs = 600_000)
            .also { it.start(CoroutineScope(Dispatchers.Default)) }

    private fun idleDials(): ForwardDials = object : ForwardDials {
        override val isClientDialed: Boolean = false
        override val oldestActiveHandshakeNanos: Long? = null
        override val lastLongLivedDialNanos: Long? = null
        override val failedDials: Long = 0
    }

    /** An IDE server that reports "terminated": without the guard, teardown would stop the workspace. */
    private fun terminatingServer(): RemoteIDEServer = mockk(relaxed = true) {
        coEvery { waitServerTerminated(any()) } returns true
    }
}
