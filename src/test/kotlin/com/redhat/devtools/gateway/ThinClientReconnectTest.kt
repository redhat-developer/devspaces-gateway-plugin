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

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.redhat.devtools.gateway.server.JoinLinkMissingException
import com.redhat.devtools.gateway.server.RemoteIDEServer
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ThinClientReconnectTest {

    companion object {
        private val START_VERIFY_TIMEOUT: Duration = 20.milliseconds
        private val START_VERIFY_POLL: Duration = 5.milliseconds
    }

    private lateinit var remoteIdeServer: RemoteIDEServer
    private lateinit var oldClient: ThinClientHandle
    private lateinit var newClient: ThinClientHandle
    private lateinit var sessionCtx: ThinClientSessionContext

    /** Alias for the flag owned by the session context under test. */
    private val reconnecting
        get() = sessionCtx.reconnecting

    @BeforeEach
    fun beforeEach() {
        remoteIdeServer = mockk()
        oldClient = mockk(relaxed = true)
        newClient = mockk(relaxed = true)
        sessionCtx = createSessionContext()
        sessionCtx.thinClient = oldClient
        every { remoteIdeServer.refreshPod() } returns podNamed("new-pod")
        every { oldClient.lifetime } returns Lifetime.Eternal
        every { oldClient.clientPresent } returns true
        every { newClient.lifetime } returns Lifetime.Eternal
        every { newClient.clientPresent } returns true
    }

    @Test
    fun `#onPodRoll replaces the thin client instead of healing it`() = runTest {
        // notifyReconnect only restores gateway websockets (clientPresent); it does not
        // relaunch the Guest window after a pod roll. Replacement starts a new client.
        stubJoinLink("tcp://localhost:5990?fp=NEW")
        var replacedWith: ThinClientHandle? = null

        val reconnect = createReconnect(
            onClientHandleReplaced = { replacedWith = it },
            startThinClient = { _, _ -> newClient },
        )

        reconnect.onPodRoll(podNamed("new-pod"))

        verify(exactly = 0) {
            oldClient.updateJoinLink(any(), any())
            oldClient.notifyReconnect()
        }
        verify(exactly = 1) { oldClient.close() }
        assertThat(sessionCtx.handlesClosedByReconnect).contains(oldClient)
        assertThat(replacedWith).isSameAs(newClient)
        assertThat(sessionCtx.thinClient).isSameAs(newClient)
        assertThat(reconnecting.get()).isFalse()
    }

    @Test
    fun `#onPodRoll tears down the session on an unexpected awaitJoinLink failure`() = runTest {
        coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } throws
            RuntimeException("unexpected join link failure")
        var closedHandle: ThinClientHandle? = null
        var closedContext: ThinClientSessionContext? = null

        val reconnect = createReconnect(
            onClientClosed = { handle, ctx ->
                closedHandle = handle
                closedContext = ctx
            },
        )

        reconnect.onPodRoll(podNamed("new-pod"))

        assertThat(closedHandle).isSameAs(oldClient)
        assertThat(closedContext).isSameAs(sessionCtx)
        assertThat(reconnecting.get()).isFalse()
    }

    @Test
    fun `#onPodRoll starts a new client when no handle exists`() = runTest {
        stubJoinLink("tcp://localhost:5990?fp=NEW")
        sessionCtx.thinClient = null
        var replacedWith: ThinClientHandle? = null

        val reconnect = createReconnect(
            onClientHandleReplaced = { replacedWith = it },
            startThinClient = { _, _ -> newClient },
        )

        reconnect.onPodRoll(podNamed("new-pod"))

        assertThat(replacedWith).isSameAs(newClient)
        assertThat(sessionCtx.thinClient).isSameAs(newClient)
        assertThat(reconnecting.get()).isFalse()
    }

    @Test
    fun `#onPodRoll skips duplicate work when reconnecting flag is set`() = runTest {
        reconnecting.set(true)

        val reconnect = createReconnect(
            startThinClient = { _, _ -> newClient },
        )

        reconnect.onPodRoll(podNamed("new-pod"))

        coVerify(exactly = 0) { remoteIdeServer.refreshPod() }
        coVerify(exactly = 0) { remoteIdeServer.awaitJoinLink(checkCancelled = any()) }
        verify(exactly = 0) {
            oldClient.updateJoinLink(any(), any())
            oldClient.notifyReconnect()
            oldClient.close()
        }
        assertThat(reconnecting.get()).isTrue()
    }

    @Test
    fun `#onPodRoll tears down session when transient retries are exhausted`() = runTest {
        coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } throws
            IOException("server unavailable")
        var closedHandle: ThinClientHandle? = null
        var closedContext: ThinClientSessionContext? = null
        var startCalled = false

        val reconnect = createReconnect(
            onClientClosed = { handle, ctx ->
                closedHandle = handle
                closedContext = ctx
            },
            startThinClient = { _, _ ->
                startCalled = true
                newClient
            },
        )

        reconnect.onPodRoll(podNamed("new-pod"))

        coVerify(exactly = 3) { remoteIdeServer.awaitJoinLink(checkCancelled = any()) }
        assertThat(startCalled).isFalse()
        assertThat(closedHandle).isSameAs(oldClient)
        assertThat(closedContext).isSameAs(sessionCtx)
        assertThat(reconnecting.get()).isFalse()
    }

    @Test
    fun `#onPodRoll retries a missing join link and tears down when retries are exhausted`() = runTest {
        coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } throws
            JoinLinkMissingException("IDE server is ready but reports no join link")
        var clientClosedCalled = false

        val reconnect = createReconnect(
            onClientClosed = { _, _ -> clientClosedCalled = true },
        )

        reconnect.onPodRoll(podNamed("new-pod"))

        coVerify(exactly = 3) { remoteIdeServer.awaitJoinLink(checkCancelled = any()) }
        assertThat(clientClosedCalled).isTrue()
        assertThat(reconnecting.get()).isFalse()
    }

    @Test
    fun `#onPodRoll recovers when the join link is missing on the first attempt`() = runTest {
        var awaitJoinLinkCalls = 0
        coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } answers {
            if (awaitJoinLinkCalls++ == 0) {
                throw JoinLinkMissingException("IDE server is ready but reports no join link")
            }
            "tcp://localhost:5990?fp=NEW"
        }
        var replacedWith: ThinClientHandle? = null
        var clientClosedCalled = false

        val reconnect = createReconnect(
            onClientHandleReplaced = { replacedWith = it },
            onClientClosed = { _, _ -> clientClosedCalled = true },
            startThinClient = { _, _ -> newClient },
        )

        reconnect.onPodRoll(podNamed("new-pod"))

        coVerify(exactly = 2) { remoteIdeServer.awaitJoinLink(checkCancelled = any()) }
        verify(exactly = 1) { oldClient.close() }
        assertThat(replacedWith).isSameAs(newClient)
        assertThat(clientClosedCalled).isFalse()
        assertThat(reconnecting.get()).isFalse()
    }

    @Test
    fun `#onPodRoll does not retry a permanent ApiException`() = runTest {
        coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } throws
            ApiException(403, "Forbidden")
        var clientClosedCalled = false

        val reconnect = createReconnect(
            onClientClosed = { _, _ -> clientClosedCalled = true },
        )

        reconnect.onPodRoll(podNamed("new-pod"))

        coVerify(exactly = 1) { remoteIdeServer.awaitJoinLink(checkCancelled = any()) }
        assertThat(clientClosedCalled).isTrue()
    }

    @Test
    fun `#onPodRoll retries when the replacement client does not start then succeeds`() = runTest {
        stubJoinLink("tcp://localhost:5990?fp=NEW")
        val firstAttempt = mockk<ThinClientHandle>(relaxed = true)
        every { firstAttempt.lifetime } returns Lifetime.Eternal
        every { firstAttempt.clientPresent } returns false
        every { newClient.clientPresent } returns true
        var startCalls = 0
        var replacedWith: ThinClientHandle? = null
        var clientClosedCalled = false

        val reconnect = createReconnect(
            onClientHandleReplaced = { replacedWith = it },
            onClientClosed = { _, _ -> clientClosedCalled = true },
            startThinClient = { _, _ ->
                if (startCalls++ == 0) firstAttempt else newClient
            },
        )

        reconnect.onPodRoll(podNamed("new-pod"))

        coVerify(exactly = 2) { remoteIdeServer.awaitJoinLink(checkCancelled = any()) }
        verify(exactly = 1) { firstAttempt.close() }
        assertThat(sessionCtx.handlesClosedByReconnect).contains(firstAttempt)
        assertThat(replacedWith).isSameAs(newClient)
        assertThat(clientClosedCalled).isFalse()
        assertThat(reconnecting.get()).isFalse()
    }

    @Test
    fun `#onPodRoll fails the reconnect when the replacement client never starts`() = runTest {
        stubJoinLink("tcp://localhost:5990?fp=NEW")
        every { newClient.clientPresent } returns false
        var clientClosedCalled = false

        val reconnect = createReconnect(
            onClientClosed = { _, _ -> clientClosedCalled = true },
            startThinClient = { _, _ -> newClient },
        )

        reconnect.onPodRoll(podNamed("new-pod"))

        // failed replacements are closed and registered so their close events cannot abort recovery
        verify(atLeast = 1) { newClient.close() }
        assertThat(sessionCtx.handlesClosedByReconnect).contains(newClient)
        assertThat(clientClosedCalled).isTrue()
        assertThat(reconnecting.get()).isFalse()
    }

    @Test
    fun `#onPodRoll seeds the rolled pod when refresh fails`() = runTest {
        val rolledPod = podNamed("rolled-pod")
        every { remoteIdeServer.refreshPod() } throws IOException("pod gone")
        every { remoteIdeServer.setPod(any()) } returns Unit
        stubJoinLink("tcp://localhost:5990?fp=NEW")
        var replacedWith: ThinClientHandle? = null

        val reconnect = createReconnect(
            onClientHandleReplaced = { replacedWith = it },
            startThinClient = { _, _ -> newClient },
        )

        reconnect.onPodRoll(rolledPod)

        verify(exactly = 1) { remoteIdeServer.setPod(rolledPod) }
        verify(exactly = 1) { oldClient.close() }
        assertThat(replacedWith).isSameAs(newClient)
        assertThat(reconnecting.get()).isFalse()
    }

    @Test
    fun `#onPodRoll clears reconnecting flag and tears down on cancellation`() = runTest {
        coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } throws
            CancellationException("cancelled")
        var clientClosedCalled = false

        val reconnect = createReconnect(
            onClientClosed = { _, _ -> clientClosedCalled = true },
        )

        var caught: CancellationException? = null
        try {
            reconnect.onPodRoll(podNamed("new-pod"))
        } catch (e: CancellationException) {
            caught = e
        }

        assertThat(caught).isNotNull
        assertThat(clientClosedCalled).isTrue()
        assertThat(reconnecting.get()).isFalse()
    }

    @Test
    fun `#execute runs the background task and reconnects`() = runTest {
        stubJoinLink("tcp://localhost:5990?fp=NEW")
        val indicator = activeIndicator()

        val taskTitle = runExecuteWithProgressMock(indicator) {
            createReconnect(startThinClient = { _, _ -> newClient }).execute(podNamed("new-pod"))
        }

        // pinned so the reconnect indicator stays distinguishable from ForwardRecoveryProgress's
        assertThat(taskTitle).isEqualTo("Reconnecting to workspace")
        verify { indicator.text = "Waiting for IDE to be ready..." }
        verify { indicator.text = "Closing IDE connection..." }
        verify { indicator.text = "Connecting to IDE..." }
        verify(exactly = 1) { oldClient.close() }
        assertThat(reconnecting.get()).isFalse()
    }

    @Test
    fun `#cancel prevents a not yet started reconnect`() = runTest {
        stubJoinLink("tcp://localhost:5990?fp=NEW")

        val reconnect = createReconnect()

        reconnect.cancel()
        reconnect.onPodRoll(podNamed("new-pod"))

        coVerify(exactly = 0) { remoteIdeServer.awaitJoinLink(checkCancelled = any()) }
        verify(exactly = 0) { oldClient.close() }
        assertThat(reconnecting.get()).isFalse()
    }

    @Test
    fun `#cancel aborts an in-flight reconnect and tears down the session`() = runTest {
        var reconnectToCancel: ThinClientReconnect? = null
        coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } answers {
            reconnectToCancel?.cancel()
            "tcp://localhost:5990?fp=NEW"
        }
        var clientClosedCalled = false

        val reconnect = createReconnect(
            onClientClosed = { _, _ -> clientClosedCalled = true },
        )
        reconnectToCancel = reconnect

        runCatching { reconnect.onPodRoll(podNamed("new-pod")) }

        assertThat(clientClosedCalled).isTrue()
        assertThat(reconnecting.get()).isFalse()
    }

    private fun activeIndicator(): ProgressIndicator = mockk(relaxed = true) {
        every { isRunning } returns true
        every { isCanceled } returns false
    }

    private fun stubJoinLink(joinLink: String) {
        coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } returns joinLink
    }

    private fun createSessionContext(): ThinClientSessionContext =
        ThinClientSessionContext(
            localPort = 12_345,
            remoteIdeServer = remoteIdeServer,
            onConnected = {},
            onDisconnected = {},
            onDevWorkspaceStopped = {},
        )

    private fun createReconnect(
        onClientHandleReplaced: (ThinClientHandle) -> Unit = {},
        onClientClosed: (ThinClientHandle?, ThinClientSessionContext) -> Unit = { _, _ -> },
        startThinClient: suspend (String, ThinClientSessionContext) -> ThinClientHandle = { _, _ ->
            newClient
        },
    ): ThinClientReconnect =
        ThinClientReconnect(
            remoteIdeServer = remoteIdeServer,
            sessionCtx = sessionCtx,
            startThinClient = startThinClient,
            onClientHandleReplaced = onClientHandleReplaced,
            onClientClosed = onClientClosed,
            verification = HealVerification(timeout = START_VERIFY_TIMEOUT, poll = START_VERIFY_POLL),
        )

    private fun podNamed(name: String): V1Pod =
        V1Pod().metadata(V1ObjectMeta().name(name).uid("uid-$name"))

    /** Runs [body] with [ProgressManager] mocked; returns the title of the task handed to it. */
    private fun runExecuteWithProgressMock(indicator: ProgressIndicator, body: () -> Unit): String? {
        var taskTitle: String? = null
        mockkStatic(ProgressManager::class)
        try {
            val progressManager = mockk<ProgressManager>()
            every { ProgressManager.getInstance() } returns progressManager
            every { progressManager.run(any<Task.Backgroundable>()) } answers {
                val task = firstArg<Task.Backgroundable>()
                taskTitle = task.title
                task.run(indicator)
            }
            body()
        } finally {
            unmockkStatic(ProgressManager::class)
        }
        return taskTitle
    }
}
