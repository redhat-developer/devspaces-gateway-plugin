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
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

class ForwardRecoveryProgressTest {

    private lateinit var scope: CoroutineScope

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `#onPodUnavailable is suppressed while reconnecting`() = runBlocking {
        scope = CoroutineScope(SupervisorJob())
        val canceled = AtomicBoolean(false)
        val progress = ForwardRecoveryProgress(
            scope = scope,
            sessionCtx = sessionContext(reconnecting = true),
            isWorkspaceRestartInProgress = { false },
            onCanceled = { canceled.set(true) },
            showAfter = 50.milliseconds,
        )

        progress.onPodUnavailable()
        delay(100.milliseconds)

        assertThat(canceled).isFalse()
    }

    @Test
    fun `#onPodUnavailable is suppressed while workspace restart is in progress`() = runBlocking {
        scope = CoroutineScope(SupervisorJob())
        val canceled = AtomicBoolean(false)
        val progress = ForwardRecoveryProgress(
            scope = scope,
            sessionCtx = sessionContext(),
            isWorkspaceRestartInProgress = { true },
            onCanceled = { canceled.set(true) },
            showAfter = 50.milliseconds,
        )

        progress.onPodUnavailable()
        delay(100.milliseconds)

        assertThat(canceled).isFalse()
    }

    @Test
    fun `#onPodResolved cancels scheduled progress`() = runBlocking {
        scope = CoroutineScope(SupervisorJob())
        val canceled = AtomicBoolean(false)
        val progress = ForwardRecoveryProgress(
            scope = scope,
            sessionCtx = sessionContext(),
            isWorkspaceRestartInProgress = { false },
            onCanceled = { canceled.set(true) },
            showAfter = 100.milliseconds,
        )

        progress.onPodUnavailable()
        progress.onPodResolved()
        delay(150.milliseconds)

        assertThat(canceled).isFalse()
    }

    private fun sessionContext(reconnecting: Boolean = false): ThinClientSessionContext =
        ThinClientSessionContext(
            localPort = 42_000,
            remoteIdeServer = mockk(relaxed = true),
            forwarder = null,
            onConnected = {},
            onDisconnected = {},
            onDevWorkspaceStopped = {},
            checkCancelled = null,
            reconnecting = AtomicBoolean(reconnecting),
        )
}
