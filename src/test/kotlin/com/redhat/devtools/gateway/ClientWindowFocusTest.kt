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

import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

class ClientWindowFocusTest {

    private val thinClient = mockk<ThinClientHandle>(relaxed = true)

    @Test
    fun `requests the window focus`() {
        val focus = ClientWindowFocus(nowMillis = { 0L })

        focus.requestFocus(thinClient)

        verify(exactly = 1) { thinClient.focusClientWindow() }
    }

    @Test
    fun `focuses at most once per interval`() {
        var now = 0L
        val focus = ClientWindowFocus(nowMillis = { now }, intervalMillis = 1_000)

        focus.requestFocus(thinClient)
        now = 999
        focus.requestFocus(thinClient)

        verify(exactly = 1) { thinClient.focusClientWindow() }
    }

    @Test
    fun `focuses again once the interval elapsed`() {
        var now = 0L
        val focus = ClientWindowFocus(nowMillis = { now }, intervalMillis = 1_000)

        focus.requestFocus(thinClient)
        now = 1_000
        focus.requestFocus(thinClient)

        verify(exactly = 2) { thinClient.focusClientWindow() }
    }

    @Test
    fun `resetThrottle allows the next request at once`() {
        val focus = ClientWindowFocus(nowMillis = { 0L }, intervalMillis = 1_000)

        focus.requestFocus(thinClient)
        focus.resetThrottle()
        focus.requestFocus(thinClient)

        verify(exactly = 2) { thinClient.focusClientWindow() }
    }

    @Test
    fun `ignores a client that can no longer be focused`() {
        every { thinClient.focusClientWindow() } throws IllegalStateException("client closed")
        val focus = ClientWindowFocus(nowMillis = { 0L })

        focus.requestFocus(thinClient) // does not throw
    }

    @Test
    fun `requests focus on the caller thread`() {
        val focusThread = AtomicReference<Thread>()
        every { thinClient.focusClientWindow() } answers { focusThread.set(Thread.currentThread()) }
        val focus = ClientWindowFocus(nowMillis = { 0L })

        focus.requestFocus(thinClient)

        assertThat(focusThread.get()).isSameAs(Thread.currentThread())
        verify(exactly = 1) { thinClient.focusClientWindow() }
    }
}
