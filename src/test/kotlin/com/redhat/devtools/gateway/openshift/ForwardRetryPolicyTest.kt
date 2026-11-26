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
package com.redhat.devtools.gateway.openshift

import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException

class ForwardRetryPolicyTest {

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val CONNECT_BUDGET_OVERRUN_MS = 3_000L
        private const val OUTAGE_BUDGET_OVERRUN_MS = 1_000L
        private const val LONG_RUNNING_ATTEMPT = 42
    }

    private val pod = V1Pod().apply {
        metadata = V1ObjectMeta().apply { name = "luke-skywalker" }
    }
    private val ports = PortMapping(41_234, 5990)
    private val cause = IOException("connection reset")

    @Test
    fun `#decide connect-time retries up to the attempt cap`(): Unit = runBlocking {
        // given
        val policy = policy(PodLiveness.RUNNING)
        val attemptAtCap = ForwardRetryPolicy.CONNECT_ATTEMPTS

        // when
        val firstRetry = policy.decide(pod, ports, false, handshake(1))
        val secondRetry = policy.decide(pod, ports, false, handshake(2))
        val atCap = policy.decide(pod, ports, false, handshake(attemptAtCap))

        // then
        assertThat(firstRetry).isEqualTo(RetryDecision.RETRY)
        assertThat(secondRetry).isEqualTo(RetryDecision.RETRY)
        assertThat(atCap).isEqualTo(RetryDecision.ATTEMPTS_EXHAUSTED)
    }

    @Test
    fun `#decide connect-time budget exhaustion is terminal`(): Unit = runBlocking {
        // given, attempt 2 is below the attempt cap: only the 2s budget can stop it
        val policy = policy(PodLiveness.UNKNOWN)
        val firstFailureNanos = System.nanoTime() - CONNECT_BUDGET_OVERRUN_MS * NANOS_PER_MILLI

        // when
        val retry = policy.decide(pod, ports, false, handshake(2, firstFailureNanos))

        // then
        assertThat(retry).isEqualTo(RetryDecision.BUDGET_EXHAUSTED)
    }

    @Test
    fun `#decide steady-state retries past the connect-time attempt cap`(): Unit = runBlocking {
        // given
        val policy = policy(PodLiveness.RUNNING)
        val pastConnectCap = ForwardRetryPolicy.CONNECT_ATTEMPTS + 1

        // when
        val pastCapRetry = policy.decide(pod, ports, true, handshake(pastConnectCap))
        val longRunningRetry = policy.decide(pod, ports, true, handshake(LONG_RUNNING_ATTEMPT))

        // then
        assertThat(pastCapRetry).isEqualTo(RetryDecision.RETRY)
        assertThat(longRunningRetry).isEqualTo(RetryDecision.RETRY)
    }

    @Test
    fun `#decide steady-state retries while the pod liveness is unknown`(): Unit = runBlocking {
        // given
        val policy = policy(PodLiveness.UNKNOWN)

        // when
        val retry = policy.decide(pod, ports, true, handshake(LONG_RUNNING_ATTEMPT))

        // then
        assertThat(retry).isEqualTo(RetryDecision.RETRY)
    }

    @Test
    fun `#decide steady-state outage budget exhaustion is terminal`(): Unit = runBlocking {
        // given
        val policy = policy(PodLiveness.RUNNING)
        val firstFailureNanos = System.nanoTime() -
            (ForwardRetryPolicy.STEADY_STATE_OUTAGE_BUDGET_MS + OUTAGE_BUDGET_OVERRUN_MS) *
            NANOS_PER_MILLI

        // when
        val retry = policy.decide(pod, ports, true, handshake(LONG_RUNNING_ATTEMPT, firstFailureNanos))

        // then
        assertThat(retry).isEqualTo(RetryDecision.BUDGET_EXHAUSTED)
    }

    @Test
    fun `#decide a gone pod is terminal in both phases`(): Unit = runBlocking {
        // given
        val policy = policy(PodLiveness.GONE)

        // when
        val connectTimeRetry = policy.decide(pod, ports, false, handshake(1))
        val steadyStateRetry = policy.decide(pod, ports, true, handshake(LONG_RUNNING_ATTEMPT))

        // then
        assertThat(connectTimeRetry).isEqualTo(RetryDecision.POD_GONE)
        assertThat(steadyStateRetry).isEqualTo(RetryDecision.POD_GONE)
    }

    @Test
    fun `#decide steady-state outage budget ends retargeting of a gone pod`(): Unit = runBlocking {
        // given
        val policy = policy(PodLiveness.GONE)
        val firstFailureNanos = System.nanoTime() -
            (ForwardRetryPolicy.STEADY_STATE_OUTAGE_BUDGET_MS + OUTAGE_BUDGET_OVERRUN_MS) *
            NANOS_PER_MILLI

        // when
        val retry = policy.decide(pod, ports, true, handshake(LONG_RUNNING_ATTEMPT, firstFailureNanos))

        // then
        assertThat(retry).isEqualTo(RetryDecision.BUDGET_EXHAUSTED)
    }

    @Test
    fun `#decide connect-time still reports a gone pod after its retry budget elapsed`(): Unit =
        runBlocking {
            // given
            val policy = policy(PodLiveness.GONE)
            val firstFailureNanos = System.nanoTime() - CONNECT_BUDGET_OVERRUN_MS * NANOS_PER_MILLI

            // when
            val retry = policy.decide(pod, ports, false, handshake(2, firstFailureNanos))

            // then
            assertThat(retry).isEqualTo(RetryDecision.POD_GONE)
        }

    @Test
    fun `#reconnectDelayMs connect-time keeps the fixed reconnect delay`() {
        // given
        val policy = policy(PodLiveness.RUNNING)

        // when
        val firstAttemptDelay = policy.reconnectDelayMs(1, established = false)
        val longRunningAttemptDelay = policy.reconnectDelayMs(LONG_RUNNING_ATTEMPT, established = false)

        // then
        assertThat(firstAttemptDelay).isEqualTo(ForwardRetryPolicy.RECONNECT_DELAY_MS)
        assertThat(longRunningAttemptDelay).isEqualTo(ForwardRetryPolicy.RECONNECT_DELAY_MS)
    }

    @Test
    fun `#reconnectDelayMs steady-state backs off from one second to the cap`() {
        // given
        val policy = policy(PodLiveness.RUNNING)

        // when
        val firstFiveAttempts = (1..5).map { policy.reconnectDelayMs(it, established = true) }
        val longRunningAttemptDelay = policy.reconnectDelayMs(LONG_RUNNING_ATTEMPT, established = true)

        // then
        assertThat(firstFiveAttempts).containsExactly(1000L, 2000L, 3000L, 5000L, 5000L)
        assertThat(longRunningAttemptDelay).isEqualTo(ForwardRetryPolicy.STEADY_STATE_MAX_DELAY_MS)
    }

    @Test
    fun `#reconnectDelayMs an attempt number below one is rejected`() {
        // given
        val policy = policy(PodLiveness.RUNNING)

        // when, then
        assertThatThrownBy { policy.reconnectDelayMs(0, established = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun handshake(
        attempt: Int,
        firstFailureNanos: Long = System.nanoTime()
    ): FailedHandshake = FailedHandshake(attempt, firstFailureNanos, cause)

    private fun policy(liveness: PodLiveness): ForwardRetryPolicy = ForwardRetryPolicy { liveness }
}
