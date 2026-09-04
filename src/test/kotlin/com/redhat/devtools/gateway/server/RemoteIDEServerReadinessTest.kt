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
package com.redhat.devtools.gateway.server

import com.redhat.devtools.gateway.util.ExponentialBackoff
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class RemoteIDEServerReadinessTest {

    @Test
    fun `#waitFor returns true when becomes ready`() = runBlocking {
        val isReady = mockk<suspend ((() -> Unit)?) -> Boolean>()
        coEvery { isReady(any()) } returns true

        val readiness = RemoteIDEServerReadiness(
            targetDescription = { "test" },
            isReady = isReady,
        )

        val result = readiness.waitFor(isReadyState = true, timeout = 5)

        assertThat(result).isTrue
        Unit
    }

    @Test
    fun `#waitFor returns false on timeout`() = runBlocking {
        val isReady = mockk<suspend ((() -> Unit)?) -> Boolean>()
        coEvery { isReady(any()) } returns false

        val readiness = RemoteIDEServerReadiness(
            targetDescription = { "test" },
            isReady = isReady,
        )

        val result = readiness.waitFor(isReadyState = true, timeout = 1)

        assertThat(result).isFalse
        Unit
    }

    @Test
    fun `#waitFor propagates CancellationException from checkCancelled`() = runBlocking {
        val isReady = mockk<suspend ((() -> Unit)?) -> Boolean>()
        coEvery { isReady(any()) } returns false

        val readiness = RemoteIDEServerReadiness(
            targetDescription = { "test" },
            isReady = isReady,
        )

        assertThrows<CancellationException> {
            readiness.waitFor(isReadyState = true, timeout = 5) {
                throw CancellationException("User cancelled")
            }
        }
        Unit
    }

    @Test
    fun `#waitFor treats non-terminal isReady exception as not ready`() = runBlocking {
        val isReady = mockk<suspend ((() -> Unit)?) -> Boolean>()
        coEvery { isReady(any()) } throws IOException("transient error")

        val readiness = RemoteIDEServerReadiness(
            targetDescription = { "test" },
            isReady = isReady,
        )

        // should not throw — exception is caught and treated as "not ready"
        val result = readiness.waitFor(isReadyState = true, timeout = 1)
        assertThat(result).isFalse
        Unit
    }

    @Test
    fun `#waitFor rethrows ServerContainerNotFoundException from isReady`() = runBlocking {
        val isReady = mockk<suspend ((() -> Unit)?) -> Boolean>()
        coEvery { isReady(any()) } throws ServerContainerNotFoundException("container gone")

        val readiness = RemoteIDEServerReadiness(
            targetDescription = { "test" },
            isReady = isReady,
        )

        assertThrows<ServerContainerNotFoundException> {
            readiness.waitFor(isReadyState = true, timeout = 5)
        }
        Unit
    }

    @Test
    fun `#waitFor rethrows CancellationException from isReady`() = runBlocking {
        val isReady = mockk<suspend ((() -> Unit)?) -> Boolean>()
        coEvery { isReady(any()) } throws CancellationException("cancelled")

        val readiness = RemoteIDEServerReadiness(
            targetDescription = { "test" },
            isReady = isReady,
        )

        assertThrows<CancellationException> {
            readiness.waitFor(isReadyState = true, timeout = 5)
        }
        Unit
    }

    @Test
    fun `#waitFor rethrows ServerContainerNotFoundException from refresh`() = runBlocking {
        val isReady = mockk<suspend ((() -> Unit)?) -> Boolean>()
        coEvery { isReady(any()) } returns false
        val refresh = mockk<() -> Unit>()
        every { refresh() } throws ServerContainerNotFoundException("container gone")

        val readiness = RemoteIDEServerReadiness(
            targetDescription = { "test" },
            isReady = isReady,
            refresh = refresh,
        )

        assertThrows<ServerContainerNotFoundException> {
            readiness.waitFor(isReadyState = true, timeout = 5)
        }
        Unit
    }

    @Test
    fun `#waitFor retries refresh on transient failure`() = runBlocking {
        val isReady = mockk<suspend ((() -> Unit)?) -> Boolean>()
        coEvery { isReady(any()) } returns false

        var refreshCalls = 0
        val refresh = mockk<() -> Unit>()
        every { refresh() } answers {
            refreshCalls++
            if (refreshCalls < 3) throw IOException("transient") else Unit
        }

        val readiness = RemoteIDEServerReadiness(
            targetDescription = { "test" },
            isReady = isReady,
            refresh = refresh,
        )

        val result = readiness.waitFor(isReadyState = true, timeout = 2)
        assertThat(result).isFalse
        assertThat(refreshCalls).isGreaterThanOrEqualTo(3)
        Unit
    }

    @Test
    fun `#waitFor keeps growing backoff while not ready despite successful refresh`() = runBlocking {
        val probeTimes = mutableListOf<Long>()
        val isReady = mockk<suspend ((() -> Unit)?) -> Boolean>()
        coEvery { isReady(any()) } answers {
            probeTimes.add(System.nanoTime())
            false
        }
        val refresh = mockk<() -> Unit>()
        every { refresh() } returns Unit

        val backoff = ExponentialBackoff()

        val readiness = RemoteIDEServerReadiness(
            targetDescription = { "test" },
            isReady = isReady,
            refresh = refresh,
            backoff = backoff,
        )

        val result = readiness.waitFor(isReadyState = true, timeout = 3)
        assertThat(result).isFalse

        assertThat(probeTimes).hasSize(3)
        val gaps = probeTimes.zipWithNext { a, b -> (b - a) / 1_000_000 }
        // refresh success must NOT reset the backoff: 500ms then 1000ms,
        // not a constant 500ms polling rate
        assertThat(gaps[0]).isBetween(400L, 900L)
        assertThat(gaps[1]).isBetween(800L, 1900L)
        Unit
    }

    @Test
    fun `#waitFor resets refresh failure counter on success`() = runBlocking {
        var refreshCalls = 0
        val refresh = mockk<() -> Unit>()
        every { refresh() } answers {
            refreshCalls++
            // fail first 2, succeed, fail 2 more — counter resets, never reaches threshold
            if (refreshCalls == 1 || refreshCalls == 2 || refreshCalls == 4 || refreshCalls == 5) {
                throw IOException("transient")
            }
        }

        val isReady = mockk<suspend ((() -> Unit)?) -> Boolean>()
        coEvery { isReady(any()) } answers { refreshCalls >= 6 }

        val readiness = RemoteIDEServerReadiness(
            targetDescription = { "test" },
            isReady = isReady,
            refresh = refresh,
        )

        val result = readiness.waitFor(isReadyState = true, timeout = 20)
        assertThat(result).isTrue
        // 2 fail + 1 success + 2 fail + 1 success, then isReady exits.
        // timeout must cover the growing backoff delays (0.5+1+2+4+5 = 12.5s).
        assertThat(refreshCalls).isEqualTo(6)
        Unit
    }

    @Test
    fun `#waitFor skips refresh when waiting for termination`() = runBlocking {
        val isReady = mockk<suspend ((() -> Unit)?) -> Boolean>()
        coEvery { isReady(any()) } returns false
        val refresh = mockk<() -> Unit>()

        val readiness = RemoteIDEServerReadiness(
            targetDescription = { "test" },
            isReady = isReady,
            refresh = refresh,
        )

        val result = readiness.waitFor(isReadyState = false, timeout = 5)
        assertThat(result).isTrue
        verify(exactly = 0) { refresh() }
        Unit
    }

    @Test
    fun `#waitFor skips refresh when no refresh callback`() = runBlocking {
        val isReady = mockk<suspend ((() -> Unit)?) -> Boolean>()
        coEvery { isReady(any()) } returns true

        val readiness = RemoteIDEServerReadiness(
            targetDescription = { "test" },
            isReady = isReady,
        )

        val result = readiness.waitFor(isReadyState = true, timeout = 5)
        assertThat(result).isTrue
        Unit
    }

    @Test
    fun `#waitFor skips isReady when refresh fails transiently`() = runBlocking {
        val isReady = mockk<suspend ((() -> Unit)?) -> Boolean>()
        coEvery { isReady(any()) } returns false

        val refresh = mockk<() -> Unit>()
        every { refresh() } throws IOException("transient")

        val readiness = RemoteIDEServerReadiness(
            targetDescription = { "test" },
            isReady = isReady,
            refresh = refresh,
        )

        // timeout 1s: first iteration refresh fails → isReady skipped →
        // ~500ms delay leaves no time for a second iteration
        val result = readiness.waitFor(isReadyState = true, timeout = 1)
        assertThat(result).isFalse
        coVerify(exactly = 0) { isReady(any()) }
        Unit
    }

    @Test
    fun `#waitFor calls isReady after successful refresh following failures`() = runBlocking {
        val isReady = mockk<suspend ((() -> Unit)?) -> Boolean>()
        coEvery { isReady(any()) } returns true

        var refreshCalls = 0
        val refresh = mockk<() -> Unit>()
        every { refresh() } answers {
            refreshCalls++
            if (refreshCalls < 3) throw IOException("transient")
        }

        val readiness = RemoteIDEServerReadiness(
            targetDescription = { "test" },
            isReady = isReady,
            refresh = refresh,
        )

        val result = readiness.waitFor(isReadyState = true, timeout = 5)
        assertThat(result).isTrue
        assertThat(refreshCalls).isGreaterThanOrEqualTo(3)
        coVerify(atLeast = 1) { isReady(any()) }
        Unit
    }
}
