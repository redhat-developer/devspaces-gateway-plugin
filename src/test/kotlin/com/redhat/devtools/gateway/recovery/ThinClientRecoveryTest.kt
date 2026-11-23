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
package com.redhat.devtools.gateway.recovery

import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.openshift.PortForwardSession
import com.redhat.devtools.gateway.openshift.ProbePortForward
import io.kubernetes.client.openapi.models.V1Pod
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

private const val LOCAL_PORT = 41_234
private val LOCAL_JOIN_LINK: URI = URI("tcp://127.0.0.1:41234#cb=1&p=1")

/**
 * The recovery wiring's guards: teardown suppression, focus on a new episode, termination and the
 * running state [ThinClientRecovery.stopRecovery] reports. The watcher itself is mocked: its loop
 * behaviour is covered by [ThinClientRecoveryWatcherTest].
 */
class ThinClientRecoveryTest {

    private lateinit var devSpacesContext: DevSpacesContext
    private lateinit var probePortForward: ProbePortForward
    private lateinit var thinClient: ThinClientHandle
    private lateinit var workspace: DevWorkspace
    private lateinit var session: PortForwardSession
    private lateinit var sessionPod: V1Pod

    @BeforeEach
    fun beforeEach() {
        devSpacesContext = mockk(relaxed = true)
        probePortForward = { _, _ -> true }
        thinClient = mockk(relaxed = true)
        workspace = mockk(relaxed = true)
        session = mockk(relaxed = true) {
            every { localPort } returns LOCAL_PORT
        }
        sessionPod = mockk(relaxed = true)
    }

    @Test
    fun `does not start an episode nor focus while tearing down`() {
        val installed = startEpisode(startReturns = true, isTearingDown = true)

        verify(exactly = 0) { installed.watcher.start(any()) }
        verify(exactly = 0) { thinClient.focusClientWindow() }
    }

    @Test
    fun `starts an episode and focuses once when not tearing down`() {
        val installed = startEpisode(startReturns = true)

        verify(exactly = 1) { installed.watcher.start(installed.scope) }
        verify(exactly = 1) { thinClient.focusClientWindow() }
    }

    @Test
    fun `does not focus when no new episode began`() {
        val installed = startEpisode(startReturns = false)

        verify(exactly = 1) { installed.watcher.start(installed.scope) }
        verify(exactly = 0) { thinClient.focusClientWindow() }
    }

    @Test
    fun `stops recovery and cancels its scope when the client terminates`() {
        val lifetime = mockk<Lifetime>(relaxed = true)
        val termination = slot<() -> Unit>()
        every { lifetime.onTermination(capture(termination)) } returns mockk(relaxed = true)
        every { thinClient.lifetime } returns lifetime

        val recovery = recovery(isTearingDown = false)
        recovery.prepareRecovery(workspace, thinClient, session, LOCAL_JOIN_LINK, sessionPod)

        verify(exactly = 1) { lifetime.onTermination(any<() -> Unit>()) }
        assertThat(termination.isCaptured).isTrue()

        val watcher = mockk<ThinClientRecoveryWatcher>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob())
        recovery.installRecoveryWatcher(watcher, scope)

        termination.captured.invoke()

        verify(exactly = 1) { watcher.stop() }
        assertThat(scope.isActive).isFalse()

        // the termination handler cleared the watcher and scope: a later start does nothing
        recovery.startRecovery(thinClient)
        verify(exactly = 0) { watcher.start(any()) }
        verify(exactly = 0) { thinClient.focusClientWindow() }
    }

    @Test
    fun `stopRecovery reports the state before stopping`() {
        val scope = CoroutineScope(SupervisorJob())
        val watcher = mockk<ThinClientRecoveryWatcher>(relaxed = true) {
            every { isActive() } returns true
        }
        val recovery = recovery(isTearingDown = false)
        recovery.installRecoveryWatcher(watcher, scope)

        assertThat(recovery.stopRecovery()).isTrue()

        verify(exactly = 1) { watcher.stop() }
        assertThat(scope.isActive).isFalse()
    }

    @Test
    fun `stopRecovery reports false when no recovery is running`() {
        val recovery = recovery(isTearingDown = false)

        assertThat(recovery.stopRecovery()).isFalse()
    }

    @Test
    fun `client termination preserves an active recovery for the teardown that follows`() {
        // given - the Guest closes during recovery: the client lifetime ends before teardown reads
        val termination = captureTermination()
        val recovery = recovery(isTearingDown = false)
        recovery.prepareRecovery(workspace, thinClient, session, LOCAL_JOIN_LINK, sessionPod)

        var active = true
        val watcher = mockk<ThinClientRecoveryWatcher>(relaxed = true) {
            every { isActive() } answers { active }
            every { stop() } answers { active = false }
        }
        val scope = CoroutineScope(SupervisorJob())
        recovery.installRecoveryWatcher(watcher, scope)

        // when
        termination.invoke()

        // then - the loop is stopped, but teardown still learns recovery was running and withholds
        // the workspace auto-stop (§7.9)
        assertThat(scope.isActive).isFalse()
        assertThat(recovery.stopRecovery()).isTrue()
    }

    @Test
    fun `client termination leaves teardown free to stop a workspace when recovery was inactive`() {
        // given - no recovery episode ran before the Guest closed
        val termination = captureTermination()
        val recovery = recovery(isTearingDown = false)
        recovery.prepareRecovery(workspace, thinClient, session, LOCAL_JOIN_LINK, sessionPod)

        val watcher = mockk<ThinClientRecoveryWatcher>(relaxed = true) {
            every { isActive() } returns false
        }
        val scope = CoroutineScope(SupervisorJob())
        recovery.installRecoveryWatcher(watcher, scope)

        // when
        termination.invoke()

        // then - the healthy-path close still stops the workspace
        assertThat(recovery.stopRecovery()).isFalse()
    }

    private fun recovery(isTearingDown: Boolean): ThinClientRecovery =
        ThinClientRecovery(devSpacesContext, probePortForward) { isTearingDown }

    /** Wires [thinClient]'s lifetime and returns its captured termination handler. */
    private fun captureTermination(): () -> Unit {
        val lifetime = mockk<Lifetime>(relaxed = true)
        val termination = slot<() -> Unit>()
        every { lifetime.onTermination(capture(termination)) } returns mockk(relaxed = true)
        every { thinClient.lifetime } returns lifetime
        return { termination.captured.invoke() }
    }

    /** The watcher and scope of one started episode. */
    private data class InstalledRecovery(
        val watcher: ThinClientRecoveryWatcher,
        val scope: CoroutineScope
    )

    /**
     * Starts one episode against a mocked watcher whose [ThinClientRecoveryWatcher.start] returns
     * [startReturns], then cancels the scope.
     */
    private fun startEpisode(
        startReturns: Boolean,
        isTearingDown: Boolean = false
    ): InstalledRecovery {
        val scope = CoroutineScope(SupervisorJob())
        val watcher = mockk<ThinClientRecoveryWatcher>(relaxed = true) {
            every { start(any()) } returns startReturns
        }
        val recovery = recovery(isTearingDown)
        recovery.installRecoveryWatcher(watcher, scope)
        try {
            recovery.startRecovery(thinClient)
        } finally {
            scope.cancel()
        }
        return InstalledRecovery(watcher, scope)
    }
}
