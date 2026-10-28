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

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.redhat.devtools.gateway.server.RemoteIDEServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
    fun `#onPodUnavailable is suppressed while reconnecting`() {
        scope = CoroutineScope(SupervisorJob())
        val canceled = AtomicBoolean(false)
        val progress = ForwardRecoveryProgress(
            scope = scope,
            sessionCtx = sessionContext(reconnecting = true),
            isWorkspaceRestartInProgress = { false },
            onCanceled = { canceled.set(true) },
            showAfter = 50.milliseconds,
        )

        runBlocking {
            progress.onPodUnavailable()
            delay(100.milliseconds)
        }

        assertThat(canceled).isFalse()
    }

    @Test
    fun `#onPodUnavailable is suppressed while workspace restart is in progress`() {
        scope = CoroutineScope(SupervisorJob())
        val canceled = AtomicBoolean(false)
        val progress = ForwardRecoveryProgress(
            scope = scope,
            sessionCtx = sessionContext(),
            isWorkspaceRestartInProgress = { true },
            onCanceled = { canceled.set(true) },
            showAfter = 50.milliseconds,
        )

        runBlocking {
            progress.onPodUnavailable()
            delay(100.milliseconds)
        }

        assertThat(canceled).isFalse()
    }

    @Test
    fun `#onPodResolved cancels scheduled progress`() {
        scope = CoroutineScope(SupervisorJob())
        val canceled = AtomicBoolean(false)
        val progress = ForwardRecoveryProgress(
            scope = scope,
            sessionCtx = sessionContext(),
            isWorkspaceRestartInProgress = { false },
            onCanceled = { canceled.set(true) },
            showAfter = 100.milliseconds,
        )

        runBlocking {
            progress.onPodUnavailable()
            progress.onPodResolved()
            delay(150.milliseconds)
        }

        assertThat(canceled).isFalse()
    }

    @Test
    fun `#onPodUnavailable shows progress titled distinctly from the reconnect indicator`() {
        scope = CoroutineScope(SupervisorJob())
        var shownTitle: String? = null

        mockkStatic(ProgressManager::class)
        try {
            val progressManager = mockk<ProgressManager>()
            every { ProgressManager.getInstance() } returns progressManager
            every { progressManager.run(any<Task.Backgroundable>()) } answers {
                shownTitle = firstArg<Task.Backgroundable>().title
            }
            val progress = ForwardRecoveryProgress(
                scope = scope,
                sessionCtx = sessionContext(),
                isWorkspaceRestartInProgress = { false },
                onCanceled = {},
                showAfter = 10.milliseconds,
            )

            runBlocking {
                progress.onPodUnavailable()
                delay(100.milliseconds)
            }
        } finally {
            unmockkStatic(ProgressManager::class)
        }

        // distinct from ThinClientReconnect's "Reconnecting to workspace" so both indicators
        // stay distinguishable when they are up at the same time
        assertThat(shownTitle)
            .isEqualTo(ForwardRecoveryProgress.PROGRESS_TITLE)
            .isNotEqualTo("Reconnecting to workspace")
    }

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
}
