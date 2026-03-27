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
package com.redhat.devtools.gateway.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class ClipboardTokenMonitorTest {

    private lateinit var monitor: ClipboardTokenMonitor
    private lateinit var mockClipboard: MockClipboardReader
    private val detectedTokens = mutableListOf<String>()

    @BeforeEach
    fun beforeEach() {
        mockClipboard = MockClipboardReader()
        monitor = ClipboardTokenMonitor(
            pollingIntervalMs = 100,
            clipboardReader = mockClipboard
        )
        detectedTokens.clear()
    }

    @AfterEach
    fun afterEach() {
        monitor.stop()
    }

    /**
     * Mock clipboard reader for testing without requiring a display server
     */
    private class MockClipboardReader : ClipboardReader {
        private var content: String? = null

        override fun readText(): String? = content

        fun setContent(text: String?) {
            content = text
        }
    }

    @Test
    fun `#isOpenShiftToken returns true for valid token format`() {
        // given
        val validToken = "sha256~ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnop"

        // when
        val result = ClipboardTokenMonitor.isOpenShiftToken(validToken)

        // then
        assertThat(result).isTrue
    }

    @Test
    fun `#isOpenShiftToken returns true for token with underscores and dashes`() {
        // given
        val validToken = "sha256~ABC_DEF-GHI_JKL-MNO_PQR"

        // when
        val result = ClipboardTokenMonitor.isOpenShiftToken(validToken)

        // then
        assertThat(result).isTrue
    }

    @Test
    fun `#isOpenShiftToken returns false for token without prefix`() {
        // given
        val invalidToken = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnop"

        // when
        val result = ClipboardTokenMonitor.isOpenShiftToken(invalidToken)

        // then
        assertThat(result).isFalse
    }

    @Test
    fun `#isOpenShiftToken returns false for token with wrong prefix`() {
        // given
        val invalidToken = "sha512~ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnop"

        // when
        val result = ClipboardTokenMonitor.isOpenShiftToken(invalidToken)

        // then
        assertThat(result).isFalse
    }

    @Test
    fun `#isOpenShiftToken returns false for token too short`() {
        // given
        val invalidToken = "sha256~ABC" // Less than 20 chars after prefix

        // when
        val result = ClipboardTokenMonitor.isOpenShiftToken(invalidToken)

        // then
        assertThat(result).isFalse
    }

    @Test
    fun `#isOpenShiftToken returns false for token with invalid characters`() {
        // given
        val invalidToken = "sha256~ABCDEFGHIJKLMNOPQRST@#$%"

        // when
        val result = ClipboardTokenMonitor.isOpenShiftToken(invalidToken)

        // then
        assertThat(result).isFalse
    }

    @Test
    fun `#isOpenShiftToken returns false for null token`() {
        // given
        val nullToken: String? = null

        // when
        val result = ClipboardTokenMonitor.isOpenShiftToken(nullToken)

        // then
        assertThat(result).isFalse
    }

    @Test
    fun `#isOpenShiftToken returns false for empty token`() {
        // given
        val emptyToken = ""

        // when
        val result = ClipboardTokenMonitor.isOpenShiftToken(emptyToken)

        // then
        assertThat(result).isFalse
    }

    @Test
    fun `#isOpenShiftToken trims whitespace before validation`() {
        // given
        val tokenWithWhitespace = "  sha256~ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnop  "

        // when
        val result = ClipboardTokenMonitor.isOpenShiftToken(tokenWithWhitespace)

        // then
        assertThat(result).isTrue
    }

    @Test
    fun `#checkNow returns token when valid token is in clipboard`() {
        runBlocking {
            // given
            val validToken = "sha256~ValidTokenWith20PlusCharacters"
            setClipboardContent(validToken)
            delay(50.milliseconds) // Give clipboard time to update

            // when
            val result = monitor.checkNow()

            // then
            assertThat(result).isEqualTo(validToken)
        }
    }

    @Test
    fun `#checkNow returns null when invalid token is in clipboard`() {
        runBlocking {
            // given
            setClipboardContent("not a valid token")
            delay(50.milliseconds)

            // when
            val result = monitor.checkNow()

            // then
            assertThat(result).isNull()
        }
    }

    @Test
    fun `#checkNow returns null when clipboard is empty`() {
        runBlocking {
            // given
            setClipboardContent("")
            delay(50.milliseconds)

            // when
            val result = monitor.checkNow()

            // then
            assertThat(result).isNull()
        }
    }

    @Test
    fun `#checkNow does not interfere with monitoring`() {
        runBlocking {
            // given
            monitor.addListener { token -> detectedTokens.add(token) }
            val monitoringToken = "sha256~MonitoringToken123456789"
            val checkNowToken = "sha256~CheckNowToken1234567890"

            // when
            monitor.start()
            setClipboardContent(checkNowToken)
            val immediateResult = monitor.checkNow()
            delay(100.milliseconds)
            setClipboardAndWaitForDetection(monitoringToken)

            // then
            assertThat(immediateResult).isEqualTo(checkNowToken)
            assertThat(detectedTokens).contains(checkNowToken, monitoringToken)
        }
    }

    @Test
    fun `#addListener registers listener successfully`() {
        // given
        val listener = ClipboardTokenMonitor.TokenDetectedListener { token ->
            detectedTokens.add(token)
        }

        // when
        monitor.addListener(listener)

        // then - no exception thrown, listener is registered
        assertThat(detectedTokens).isEmpty()
    }

    @Test
    fun `#removeListener removes listener successfully`() {
        // given
        val listener = ClipboardTokenMonitor.TokenDetectedListener { token ->
            detectedTokens.add(token)
        }
        monitor.addListener(listener)

        // when
        monitor.removeListener(listener)

        // then - no exception thrown, listener is removed
        assertThat(detectedTokens).isEmpty()
    }

    @Test
    fun `#start begins monitoring clipboard`() {
        runBlocking {
            // given
            monitor.addListener { token -> detectedTokens.add(token) }
            val validToken = "sha256~MonitoringTestToken12345678"

            // when
            monitor.start()
            delay(150.milliseconds) // Wait less than one poll interval
            setClipboardAndWaitForDetection(validToken)

            // then
            assertThat(detectedTokens).contains(validToken)
        }
    }

    @Test
    fun `#start does not start multiple polling jobs`() {
        runBlocking {
            // given
            monitor.addListener { token -> detectedTokens.add(token) }

            // when
            monitor.start()
            monitor.start() // Start again
            monitor.start() // And again

            val validToken = "sha256~MultipleStartTestToken123"
            setClipboardAndWaitForDetection(validToken)

            // then - should only be notified once per token change
            assertThat(detectedTokens.filter { it == validToken }).hasSize(1)
        }
    }

    @Test
    fun `#stop halts monitoring`() {
        runBlocking {
            // given
            monitor.addListener { token -> detectedTokens.add(token) }
            monitor.start()
            delay(100.milliseconds)

            // when
            monitor.stop()
            val validToken = "sha256~AfterStopTestToken1234567"
            setClipboardAndWaitForDetection(validToken)

            // then - no tokens should be detected after stop
            assertThat(detectedTokens).doesNotContain(validToken)
        }
    }

    @Test
    fun `listener notified only once per unique token`() {
        runBlocking {
            // given
            monitor.addListener { token -> detectedTokens.add(token) }
            val validToken = "sha256~UniqueTokenTest123456789"

            // when
            monitor.start()
            setClipboardAndWaitForDetection(validToken)
            // Don't change clipboard content
            delay(250.milliseconds)

            // then - should only be notified once
            assertThat(detectedTokens.filter { it == validToken }).hasSize(1)
        }
    }

    @Test
    fun `listener notified for each different token`() {
        runBlocking {
            // given
            monitor.addListener { token -> detectedTokens.add(token) }
            val token1 = "sha256~FirstTokenTest1234567890"
            val token2 = "sha256~SecondTokenTest123456789"

            // when
            monitor.start()
            setClipboardAndWaitForDetection(token1)
            setClipboardAndWaitForDetection(token2)

            // then - should be notified for both
            assertThat(detectedTokens).contains(token1, token2)
        }
    }

    @Test
    fun `multiple listeners all receive notifications`() {
        runBlocking {
            // given
            val tokens1 = mutableListOf<String>()
            val tokens2 = mutableListOf<String>()
            monitor.addListener { token -> tokens1.add(token) }
            monitor.addListener { token -> tokens2.add(token) }
            val validToken = "sha256~MultiListenerTest12345678"

            // when
            monitor.start()
            setClipboardAndWaitForDetection(validToken)

            // then - both listeners should receive the token
            assertThat(tokens1).contains(validToken)
            assertThat(tokens2).contains(validToken)
        }
    }

    @Test
    fun `listener exception does not break other listeners`() {
        runBlocking {
            // given
            val tokens = mutableListOf<String>()
            monitor.addListener { throw RuntimeException("Listener failure") }
            monitor.addListener { token -> tokens.add(token) }
            val validToken = "sha256~ExceptionTestToken1234567"

            // when
            monitor.start()
            setClipboardAndWaitForDetection(validToken)

            // then - second listener should still receive notification
            assertThat(tokens).contains(validToken)
        }
    }

    @Test
    fun `monitor ignores non-token clipboard content`() {
        runBlocking {
            // given
            monitor.addListener { token -> detectedTokens.add(token) }

            // when
            monitor.start()
            setClipboardContent("Just some regular text")
            delay(250.milliseconds)
            setClipboardContent("12345")
            delay(250.milliseconds)
            setClipboardContent("sha256~short") // Too short
            delay(250.milliseconds)

            // then - no tokens should be detected
            assertThat(detectedTokens).isEmpty()
        }
    }

    @Test
    fun `monitor handles rapid clipboard changes`() {
        runBlocking {
            // given
            monitor.addListener { token -> detectedTokens.add(token) }
            val tokens = (1..5).map { "sha256~RapidChangeToken${it}234567890" }

            // when
            monitor.start()
            tokens.forEach { token ->
                setClipboardContent(token)
                delay(150.milliseconds) // Faster than polling interval
            }
            delay(300.milliseconds) // Let polling catch up

            // then - should detect at least some tokens (may not catch all due to rapid changes)
            assertThat(detectedTokens).isNotEmpty()
            tokens.forEach { token ->
                if (detectedTokens.contains(token)) {
                    assertThat(detectedTokens.filter { it == token }).hasSize(1)
                }
            }
        }
    }

    @Test
    fun `#stop can be called multiple times safely`() {
        // when
        monitor.stop()
        monitor.stop()
        monitor.stop()

        // then - no exception thrown
        assertThat(true).isTrue
    }

    @Test
    fun `monitor can be restarted after stop`() {
        runBlocking {
            // given
            monitor.addListener { token -> detectedTokens.add(token) }
            val token1 = "sha256~FirstRunToken1234567890"
            val token2 = "sha256~SecondRunToken123456789"

            // when - first run
            startSetAndStop(token1)

            // when - second run
            monitor.start()
            setClipboardAndWaitForDetection(token2)

            // then - both tokens should be detected
            assertThat(detectedTokens).contains(token1, token2)
        }
    }

    /**
     * Helper to set clipboard content for testing
     */
    private fun setClipboardContent(text: String) {
        mockClipboard.setContent(text)
    }

    /**
     * Helper to set clipboard content and wait for detection
     */
    private suspend fun setClipboardAndWaitForDetection(content: String, delayMs: Long = 250) {
        setClipboardContent(content)
        delay(delayMs.milliseconds)
    }

    /**
     * Helper to start monitor, set clipboard content, wait for detection, and stop
     */
    private suspend fun startSetAndStop(content: String, detectionDelayMs: Long = 250, stopDelayMs: Long = 100) {
        monitor.start()
        setClipboardContent(content)
        delay(detectionDelayMs.milliseconds)
        monitor.stop()
        delay(stopDelayMs.milliseconds)
    }
}
