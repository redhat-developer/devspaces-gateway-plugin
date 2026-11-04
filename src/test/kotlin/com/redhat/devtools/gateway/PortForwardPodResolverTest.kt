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

import com.redhat.devtools.gateway.server.RemoteIDEServer
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class PortForwardPodResolverTest {

    private val scope = CoroutineScope(SupervisorJob())

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `#resolve returns pod immediately when tracker is Ready`() = runBlocking {
        val pod = podWithUid("pod-a", "uid-1")
        val tracker = trackerReturning(PodResolution.Ready(pod))
        val recovery = spyk(forwardRecovery())
        val resolver = PortForwardPodResolver(tracker, sessionContext(), recovery)

        val result = resolver.resolve()

        assertThat(result).isSameAs(pod)
        verify(exactly = 1) { recovery.onPodResolved() }
    }

    @Test
    fun `#resolve waits for pod when tracker is Unavailable`() = runBlocking {
        val pod = podWithUid("pod-a", "uid-1")
        val remoteIdeServer = mockk<RemoteIDEServer>()
        var calls = 0
        coEvery { remoteIdeServer.refreshPod() } answers {
            // fast path and the post-lock quick check are both unavailable; the poll loop resolves
            if (++calls < 3) throw IOException("not running") else pod
        }
        val tracker = WorkspacePodTracker(remoteIdeServer)
        tracker.seed(podWithUid("pod-a", "uid-1"))
        val recovery = spyk(forwardRecovery())
        val resolver = PortForwardPodResolver(tracker, sessionContext(), recovery)

        val result = resolver.resolve()

        assertThat(result).isSameAs(pod)
        verify(atLeast = 1) { recovery.onPodUnavailable() }
        verify(atLeast = 1) { recovery.onPodResolved() }
    }

    @Test
    fun `#resolve returns rolled pod immediately without waiting for reconnect`() = runBlocking {
        val pod = podWithUid("pod-b", "uid-2")
        val sessionCtx = sessionContext(reconnecting = true)
        val tracker = trackerReturning(PodResolution.RollDelegated(pod))
        val recovery = spyk(forwardRecovery())
        val resolver = PortForwardPodResolver(tracker, sessionCtx, recovery)

        val result = resolver.resolve()

        assertThat(result).isSameAs(pod)
        assertThat(sessionCtx.reconnecting.get()).isTrue()
        verify(exactly = 1) { recovery.dismiss() }
        verify(exactly = 0) { recovery.onPodResolved() }
    }

    @Test
    fun `#resolve shares one poll loop between concurrent waiters`() {
        runBlocking {
            // given — pod unavailable for the first calls; the first poll is held open by a gate
            val pod = podWithUid("pod-a", "uid-1")
            val remoteIdeServer = mockk<RemoteIDEServer>()
            val refreshes = AtomicInteger()
            val releaseFirstPoll = CompletableDeferred<Unit>()
            coEvery { remoteIdeServer.refreshPod() } coAnswers {
                when (refreshes.incrementAndGet()) {
                    1, 2, 3 -> throw IOException("not running") // waiter 1 fast path + quick check, waiter 2 fast path
                    4 -> {
                        releaseFirstPoll.await()
                        pod
                    }
                    else -> pod
                }
            }
            val tracker = WorkspacePodTracker(remoteIdeServer)
            tracker.seed(pod)
            val recovery = spyk(forwardRecovery())
            val resolver = PortForwardPodResolver(tracker, sessionContext(), recovery)

            // when — two connections wait; the second must queue behind the first, not poll on its own
            val first = async { resolver.resolve() }
            yield()
            val second = async { resolver.resolve() }
            yield()

            // then — waiter 1 reached its gated poll; waiter 2 is parked on the mutex, not polling
            withTimeout(5_000) {
                while (refreshes.get() < 4) {
                    delay(50)
                }
            }
            assertThat(second.isCompleted).isFalse()

            releaseFirstPoll.complete(Unit)
            assertThat(first.await()).isSameAs(pod)
            assertThat(second.await()).isSameAs(pod)
            // waiter 1: fast path + quick check + poll; waiter 2: fast path + quick check only
            assertThat(refreshes.get()).isEqualTo(5)
        }
    }

    private fun trackerReturning(vararg outcomes: PodResolution): WorkspacePodTracker {
        val remoteIdeServer = mockk<RemoteIDEServer>(relaxed = true)
        val tracker = spyk(WorkspacePodTracker(remoteIdeServer))
        var index = 0
        coEvery { tracker.resolvePod() } answers {
            outcomes[minOf(index++, outcomes.size - 1)]
        }
        return tracker
    }

    private fun forwardRecovery(): ForwardRecoveryProgress =
        ForwardRecoveryProgress(
            scope = scope,
            sessionCtx = sessionContext(),
            isWorkspaceRestartInProgress = { false },
            onCanceled = {},
            showAfter = 60_000.milliseconds,
        )

    private fun sessionContext(reconnecting: Boolean = false): ThinClientSessionContext =
        ThinClientSessionContext(
            localPort = 42_000,
            remoteIdeServer = mockk(relaxed = true),
            onConnected = {},
            onDisconnected = {},
            onDevWorkspaceStopped = {},
        ).apply {
            this.reconnecting.set(reconnecting)
        }

    private fun podWithUid(name: String, uid: String): V1Pod =
        V1Pod().metadata(V1ObjectMeta().name(name).uid(uid))
}
