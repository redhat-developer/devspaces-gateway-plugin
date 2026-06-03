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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
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

        assertThat(result.pod).isSameAs(pod)
        verify(exactly = 1) { recovery.onPodResolved() }
    }

    @Test
    fun `#resolve waits for pod when tracker is Unavailable`() = runBlocking {
        val pod = podWithUid("pod-a", "uid-1")
        val remoteIdeServer = mockk<RemoteIDEServer>()
        var calls = 0
        coEvery { remoteIdeServer.refreshPod() } answers {
            if (++calls < 2) throw IOException("not running") else pod
        }
        val tracker = WorkspacePodTracker(remoteIdeServer)
        tracker.seed(podWithUid("pod-a", "uid-1"))
        val recovery = spyk(forwardRecovery())
        val resolver = PortForwardPodResolver(tracker, sessionContext(), recovery)

        val result = resolver.resolve()

        assertThat(result.pod).isSameAs(pod)
        verify(atLeast = 1) { recovery.onPodUnavailable() }
        verify(atLeast = 1) { recovery.onPodResolved() }
    }

    @Test
    fun `#resolve returns rolled pod immediately without waiting for reconnect`() = runBlocking {
        val pod = podWithUid("pod-b", "uid-2")
        val reconnecting = AtomicBoolean(true)
        val tracker = trackerReturning(PodResolution.RollDelegated(pod))
        val recovery = spyk(forwardRecovery())
        val resolver = PortForwardPodResolver(tracker, sessionContext(reconnecting), recovery)

        val result = resolver.resolve()

        assertThat(result.pod).isSameAs(pod)
        assertThat(reconnecting.get()).isTrue()
        verify(exactly = 1) { recovery.dismiss() }
        verify(exactly = 0) { recovery.onPodResolved() }
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

    private fun sessionContext(reconnecting: AtomicBoolean = AtomicBoolean(false)): ThinClientSessionContext =
        ThinClientSessionContext(
            localPort = 42_000,
            remoteIdeServer = mockk(relaxed = true),
            forwarder = null,
            onConnected = {},
            onDisconnected = {},
            onDevWorkspaceStopped = {},
            checkCancelled = null,
            reconnecting = reconnecting,
        )

    private fun podWithUid(name: String, uid: String): V1Pod =
        V1Pod().metadata(V1ObjectMeta().name(name).uid(uid))
}
