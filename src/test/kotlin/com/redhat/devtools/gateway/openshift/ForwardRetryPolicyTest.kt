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
    fun `connect-time retries up to the attempt cap`(): Unit = runBlocking {
        val policy = policy(PodLiveness.RUNNING)

        assertThat(policy.decide(pod, ports, false, handshake(1))).isEqualTo(RetryDecision.RETRY)
        assertThat(policy.decide(pod, ports, false, handshake(2))).isEqualTo(RetryDecision.RETRY)
        assertThat(
            policy.decide(pod, ports, false, handshake(ForwardRetryPolicy.CONNECT_ATTEMPTS))
        ).isEqualTo(RetryDecision.ATTEMPTS_EXHAUSTED)
    }

    @Test
    fun `connect-time budget exhaustion is terminal`(): Unit = runBlocking {
        val policy = policy(PodLiveness.UNKNOWN)
        val firstFailureNanos = System.nanoTime() - CONNECT_BUDGET_OVERRUN_MS * NANOS_PER_MILLI

        // attempt 2 is below the attempt cap: only the 2s budget can stop it
        assertThat(policy.decide(pod, ports, false, handshake(2, firstFailureNanos)))
            .isEqualTo(RetryDecision.BUDGET_EXHAUSTED)
    }

    @Test
    fun `steady-state retries past the connect-time attempt cap`(): Unit = runBlocking {
        val policy = policy(PodLiveness.RUNNING)
        val pastConnectCap = ForwardRetryPolicy.CONNECT_ATTEMPTS + 1

        assertThat(policy.decide(pod, ports, true, handshake(pastConnectCap)))
            .isEqualTo(RetryDecision.RETRY)
        assertThat(policy.decide(pod, ports, true, handshake(LONG_RUNNING_ATTEMPT)))
            .isEqualTo(RetryDecision.RETRY)
    }

    @Test
    fun `steady-state retries while the pod liveness is unknown`(): Unit = runBlocking {
        val policy = policy(PodLiveness.UNKNOWN)

        assertThat(policy.decide(pod, ports, true, handshake(LONG_RUNNING_ATTEMPT)))
            .isEqualTo(RetryDecision.RETRY)
    }

    @Test
    fun `steady-state outage budget exhaustion is terminal`(): Unit = runBlocking {
        val policy = policy(PodLiveness.RUNNING)
        val firstFailureNanos = System.nanoTime() -
            (ForwardRetryPolicy.STEADY_STATE_OUTAGE_BUDGET_MS + OUTAGE_BUDGET_OVERRUN_MS) *
            NANOS_PER_MILLI

        assertThat(policy.decide(pod, ports, true, handshake(LONG_RUNNING_ATTEMPT, firstFailureNanos)))
            .isEqualTo(RetryDecision.BUDGET_EXHAUSTED)
    }

    @Test
    fun `a gone pod is terminal in both phases`(): Unit = runBlocking {
        val policy = policy(PodLiveness.GONE)

        assertThat(policy.decide(pod, ports, false, handshake(1)))
            .isEqualTo(RetryDecision.POD_GONE)
        assertThat(policy.decide(pod, ports, true, handshake(LONG_RUNNING_ATTEMPT)))
            .isEqualTo(RetryDecision.POD_GONE)
    }

    @Test
    fun `connect-time keeps the fixed reconnect delay`() {
        val policy = policy(PodLiveness.RUNNING)

        assertThat(policy.reconnectDelayMs(1, established = false))
            .isEqualTo(ForwardRetryPolicy.RECONNECT_DELAY_MS)
        assertThat(policy.reconnectDelayMs(LONG_RUNNING_ATTEMPT, established = false))
            .isEqualTo(ForwardRetryPolicy.RECONNECT_DELAY_MS)
    }

    @Test
    fun `steady-state backs off from one second to the cap`() {
        val policy = policy(PodLiveness.RUNNING)
        val firstFiveAttempts = (1..5).map { policy.reconnectDelayMs(it, established = true) }

        assertThat(firstFiveAttempts).containsExactly(1000L, 2000L, 3000L, 5000L, 5000L)
        assertThat(policy.reconnectDelayMs(LONG_RUNNING_ATTEMPT, established = true))
            .isEqualTo(ForwardRetryPolicy.STEADY_STATE_MAX_DELAY_MS)
    }

    @Test
    fun `an attempt number below one is rejected`() {
        val policy = policy(PodLiveness.RUNNING)

        assertThatThrownBy { policy.reconnectDelayMs(0, established = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun handshake(
        attempt: Int,
        firstFailureNanos: Long = System.nanoTime()
    ): FailedHandshake = FailedHandshake(attempt, firstFailureNanos, cause)

    private fun policy(liveness: PodLiveness): ForwardRetryPolicy = ForwardRetryPolicy { liveness }
}
