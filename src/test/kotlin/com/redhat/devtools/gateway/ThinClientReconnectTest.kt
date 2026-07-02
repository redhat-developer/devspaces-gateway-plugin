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

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.redhat.devtools.gateway.server.RemoteIDEServer
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import io.kubernetes.client.openapi.ApiException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class ThinClientReconnectTest {

    private lateinit var remoteIdeServer: RemoteIDEServer
    private lateinit var oldClient: ThinClientHandle
    private lateinit var newClient: ThinClientHandle
    private lateinit var sessionCtx: ThinClientSessionContext
    private lateinit var reconnecting: AtomicBoolean

    @BeforeEach
    fun beforeEach() {
        remoteIdeServer = mockk()
        oldClient = mockk(relaxed = true)
        newClient = mockk(relaxed = true)
        reconnecting = AtomicBoolean(false)
        sessionCtx = createSessionContext()
        every { remoteIdeServer.refreshPod() } returns podNamed("new-pod")
    }

    @Test
    fun `#onPodRoll fetches new joinLink and calls startThinClient`() {
        runBlocking {
            stubServerReady(joinLink = "jetbrains://gateway?fp=NEW")
            var receivedJoinLink: String? = null

            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = {},
                onClientClosed = { _, _ -> },
                startThinClient = { joinLink, _ ->
                    receivedJoinLink = joinLink
                    newClient
                },
            )

            thinClientReconnect.onPodRoll(podNamed("new-pod"))

            coVerify(exactly = 1) { remoteIdeServer.refreshPod() }
            coVerify(exactly = 1) {
                remoteIdeServer.awaitJoinLink(checkCancelled = any())
            }
            assertThat(receivedJoinLink).isEqualTo("jetbrains://gateway?fp=NEW")
            assertThat(reconnecting.get()).isFalse()
        }
    }

    @Test
    fun `#onPodRoll closes existing thin client before reconnect`() {
        runBlocking {
            stubServerReady(joinLink = "jetbrains://gateway?fp=NEW")
            val closeOrder = mutableListOf<String>()

            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = { closeOrder.add("replaced") },
                onClientClosed = { _, _ -> },
                startThinClient = { _, _ ->
                    closeOrder.add("started")
                    newClient
                },
            )

            thinClientReconnect.onPodRoll(podNamed("new-pod"))

            verify { oldClient.close() }
            assertThat(closeOrder).containsExactly("started", "replaced")
            assertThat(reconnecting.get()).isFalse()
        }
    }

    @Test
    fun `#onPodRoll skips duplicate work when reconnecting flag is set`() {
        runBlocking {
            reconnecting.set(true)
            var startCalled = false

            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = {},
                onClientClosed = { _, _ -> },
                startThinClient = { _, _ ->
                    startCalled = true
                    newClient
                },
            )

            thinClientReconnect.onPodRoll(podNamed("new-pod"))

            verify(exactly = 0) { oldClient.close() }
            assertThat(startCalled).isFalse()
        }
    }

    @Test
    fun `#onPodRoll tears down session when transient retries exhausted`() {
        runBlocking {
            coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } throws
                IOException("server unavailable")

            var clientClosedCalled = false
            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = {},
                onClientClosed = { _, _ -> clientClosedCalled = true },
                startThinClient = { _, _ -> newClient },
            )

            thinClientReconnect.onPodRoll(podNamed("new-pod"))

            verify { oldClient.close() }
            assertThat(clientClosedCalled).isTrue()
            assertThat(reconnecting.get()).isFalse()
        }
    }

    @Test
    fun `#onPodRoll recovers from transient failure on retry`() {
        runBlocking {
            val attemptCount = mutableListOf<Int>()
            coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } answers {
                attemptCount.add(attemptCount.size + 1)
                if (attemptCount.size < 2) throw IOException("not ready yet")
                "jetbrains://gateway?fp=RECOVERED"
            }

            var clientClosedCalled = false
            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = {},
                onClientClosed = { _, _ -> clientClosedCalled = true },
                startThinClient = { _, _ -> newClient },
            )

            thinClientReconnect.onPodRoll(podNamed("new-pod"))

            assertThat(attemptCount).hasSize(2)
            verify { oldClient.close() }
            assertThat(clientClosedCalled).isFalse()
            assertThat(reconnecting.get()).isFalse()
        }
    }

    @Test
    fun `#onPodRoll recovers from TimeoutCancellationException on retry`() {
        runBlocking {
            val attemptCount = mutableListOf<Int>()
            coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } answers {
                attemptCount.add(attemptCount.size + 1)
                if (attemptCount.size < 2) throw timeoutCancellationException()
                "jetbrains://gateway?fp=RECOVERED"
            }

            var clientClosedCalled = false
            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = {},
                onClientClosed = { _, _ -> clientClosedCalled = true },
                startThinClient = { _, _ -> newClient },
            )

            thinClientReconnect.onPodRoll(podNamed("new-pod"))

            assertThat(attemptCount).hasSize(2)
            verify { oldClient.close() }
            assertThat(clientClosedCalled).isFalse()
            assertThat(reconnecting.get()).isFalse()
        }
    }

    @Test
    fun `#onPodRoll tears down session when timeout retries exhausted`() {
        runBlocking {
            coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } throws
                timeoutCancellationException()

            var clientClosedCalled = false
            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = {},
                onClientClosed = { _, _ -> clientClosedCalled = true },
                startThinClient = { _, _ -> newClient },
            )

            thinClientReconnect.onPodRoll(podNamed("new-pod"))

            verify { oldClient.close() }
            assertThat(clientClosedCalled).isTrue()
            assertThat(reconnecting.get()).isFalse()
        }
    }

    @Test
    fun `#onPodRoll tears down session on permanent failure with no retry`() {
        runBlocking {
            coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } throws
                IOException("no join link")

            var clientClosedCalled = false
            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = {},
                onClientClosed = { _, _ -> clientClosedCalled = true },
                startThinClient = { _, _ -> newClient },
            )

            thinClientReconnect.onPodRoll(podNamed("new-pod"))

            verify { oldClient.close() }
            coVerify(exactly = 1) { remoteIdeServer.awaitJoinLink(checkCancelled = any()) }
            assertThat(clientClosedCalled).isTrue()
            assertThat(reconnecting.get()).isFalse()
        }
    }

    @Test
    fun `#onPodRoll treats startThinClient failure as permanent and tears down`() {
        runBlocking {
            stubServerReady(joinLink = "jetbrains://gateway?fp=IRRELEVANT")

            var clientClosedCalled = false
            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = {},
                onClientClosed = { _, _ -> clientClosedCalled = true },
                startThinClient = { _, _ -> throw IllegalStateException("Could not connect, workspace IDE is not ready.") },
            )

            thinClientReconnect.onPodRoll(podNamed("new-pod"))

            verify { oldClient.close() }
            coVerify(exactly = 1) { remoteIdeServer.awaitJoinLink(checkCancelled = any()) }
            assertThat(clientClosedCalled).isTrue()
            assertThat(reconnecting.get()).isFalse()
        }
    }

    @Test
    fun `#onPodRoll treats ApiException as permanent and tears down`() {
        runBlocking {
            coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } throws
                ApiException(403, "Forbidden")

            var clientClosedCalled = false
            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = {},
                onClientClosed = { _, _ -> clientClosedCalled = true },
                startThinClient = { _, _ -> newClient },
            )

            thinClientReconnect.onPodRoll(podNamed("new-pod"))

            verify { oldClient.close() }
            coVerify(exactly = 1) { remoteIdeServer.awaitJoinLink(checkCancelled = any()) }
            assertThat(clientClosedCalled).isTrue()
            assertThat(reconnecting.get()).isFalse()
        }
    }

    @Test
    fun `#onPodRoll clears reconnecting flag before rethrowing cancellation exception`() {
        runBlocking {
            coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } throws
                CancellationException("cancelled")

            var clientClosedCalled = false
            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = {},
                onClientClosed = { _, _ -> clientClosedCalled = true },
                startThinClient = { _, _ -> newClient },
            )

            var caughtCancellation: CancellationException? = null
            try {
                thinClientReconnect.onPodRoll(podNamed("new-pod"))
            } catch (e: CancellationException) {
                caughtCancellation = e
            }

            assertThat(caughtCancellation).isNotNull
            verify { oldClient.close() }
            assertThat(clientClosedCalled).isTrue()
            assertThat(reconnecting.get()).isFalse()
        }
    }

    @Test
    fun `#onPodRoll updates progress indicator at each reconnect phase`() {
        runBlocking {
            stubServerReady(joinLink = "jetbrains://gateway?fp=NEW")
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            stubActiveProgressIndicator(indicator)

            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = {},
                onClientClosed = { _, _ -> },
                startThinClient = { _, _ -> newClient },
            )

            thinClientReconnect.onPodRoll(podNamed("new-pod"), indicator)

            verify { indicator.fraction = 0.0 }
            verify { indicator.text = "Closing IDE connection..." }
            verify { indicator.fraction = 0.5 }
            verify { indicator.text = "Waiting for IDE to be ready..." }
            verify { indicator.text2 = "Attempt 1/3" }
            verify { indicator.fraction = 1.0 }
            verify { indicator.text = "Connecting to IDE..." }
            verify { indicator.text2 = null }
        }
    }

    @Test
    fun `#execute runs Task Backgroundable and passes indicator to onPodRoll`() {
        runBlocking {
            stubServerReady(joinLink = "jetbrains://gateway?fp=NEW")
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            stubActiveProgressIndicator(indicator)

            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = {},
                onClientClosed = { _, _ -> },
                startThinClient = { _, _ -> newClient },
            )

            runExecuteWithProgressMock(indicator) {
                thinClientReconnect.execute(podNamed("new-pod"))
            }

            verify { indicator.fraction = 0.0 }
            verify { indicator.text = "Closing IDE connection..." }
            verify { indicator.fraction = 1.0 }
            verify { indicator.text = "Connecting to IDE..." }
            assertThat(reconnecting.get()).isFalse()
        }
    }

    @Test
    fun `#onPodRoll tears down session when progress is canceled`() {
        runBlocking {
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            every { indicator.isRunning } returns true
            every { indicator.isCanceled } returnsMany listOf(false, true)

            var clientClosedCalled = false
            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = {},
                onClientClosed = { _, _ -> clientClosedCalled = true },
                startThinClient = { _, _ -> newClient },
            )

            var caught: ProcessCanceledException? = null
            try {
                thinClientReconnect.onPodRoll(podNamed("new-pod"), indicator)
            } catch (e: ProcessCanceledException) {
                caught = e
            }

            assertThat(caught).isNotNull
            verify { oldClient.close() }
            verify { indicator.text2 = null }
            assertThat(clientClosedCalled).isTrue()
            assertThat(reconnecting.get()).isFalse()
        }
    }

    @Test
    fun `#onPodRoll tears down session when canceled during retry delay`() {
        runBlocking {
            var cancelOnNextProgressCheck = false
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            every { indicator.isRunning } returns true
            every { indicator.isCanceled } answers { cancelOnNextProgressCheck }

            coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } answers {
                cancelOnNextProgressCheck = true
                throw IOException("not ready yet")
            }

            var clientClosedCalled = false
            val thinClientReconnect = createThinClientReconnect(
                getCurrentClient = { oldClient },
                onClientHandleReplaced = {},
                onClientClosed = { _, _ -> clientClosedCalled = true },
                startThinClient = { _, _ -> newClient },
            )

            var caught: ProcessCanceledException? = null
            try {
                thinClientReconnect.onPodRoll(podNamed("new-pod"), indicator)
            } catch (e: ProcessCanceledException) {
                caught = e
            }

            assertThat(caught).isNotNull
            verify { oldClient.close() }
            verify { indicator.text2 = "Attempt 1/3" }
            verify { indicator.text2 = null }
            assertThat(clientClosedCalled).isTrue()
            assertThat(reconnecting.get()).isFalse()
        }
    }

    private fun stubActiveProgressIndicator(indicator: ProgressIndicator) {
        every { indicator.isRunning } returns true
        every { indicator.isCanceled } returns false
    }

    private fun createSessionContext(): ThinClientSessionContext =
        ThinClientSessionContext(
            localPort = 12_345,
            remoteIdeServer = remoteIdeServer,
            forwarder = mockk(relaxed = true),
            onConnected = {},
            onDisconnected = {},
            onDevWorkspaceStopped = {},
            checkCancelled = null,
            reconnecting = reconnecting,
        )

    private fun stubServerReady(joinLink: String) {
        coEvery { remoteIdeServer.awaitJoinLink(checkCancelled = any()) } returns joinLink
    }

    private fun createThinClientReconnect(
        getCurrentClient: () -> ThinClientHandle?,
        onClientHandleReplaced: (ThinClientHandle) -> Unit,
        onClientClosed: (ThinClientHandle?, ThinClientSessionContext) -> Unit,
        startThinClient: suspend (String, ThinClientSessionContext) -> ThinClientHandle,
    ): ThinClientReconnect =
        ThinClientReconnect(
            remoteIdeServer = remoteIdeServer,
            sessionCtx = sessionCtx,
            getCurrentClient = getCurrentClient,
            startThinClient = startThinClient,
            onClientHandleReplaced = onClientHandleReplaced,
            onClientClosed = onClientClosed,
        )

    private fun podNamed(name: String): V1Pod =
        V1Pod().metadata(V1ObjectMeta().name(name).uid("uid-$name"))

    private fun timeoutCancellationException(): TimeoutCancellationException = runBlocking {
        try {
            withTimeout(0L) { delay(Long.MAX_VALUE) }
            error("unreachable")
        } catch (e: TimeoutCancellationException) {
            e
        }
    }

    private fun runExecuteWithProgressMock(indicator: ProgressIndicator, body: () -> Unit) {
        mockkStatic(ProgressManager::class)
        try {
            val pm = mockk<ProgressManager>()
            every { ProgressManager.getInstance() } returns pm
            every { pm.run(any<com.intellij.openapi.progress.Task.Backgroundable>()) } answers {
                firstArg<com.intellij.openapi.progress.Task.Backgroundable>().run(indicator)
            }
            body()
        } finally {
            unmockkStatic(ProgressManager::class)
        }
    }
}
