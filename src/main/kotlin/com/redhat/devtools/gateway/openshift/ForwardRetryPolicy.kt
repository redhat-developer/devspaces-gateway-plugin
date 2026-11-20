/*
 * Copyright (c) 2024-2026 Red Hat, Inc.
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

import com.intellij.openapi.diagnostic.logger
import io.kubernetes.client.openapi.models.V1Pod

private const val NANOS_PER_MILLI = 1_000_000L

/** The local/remote port pair a forwarding attempt bridges. */
internal data class PortMapping(val localPort: Int, val remotePort: Int)

/** Why a failed handshake is or is not retried. */
internal enum class RetryDecision {
    RETRY,

    /**
     * The pod the failed attempt used is gone. Terminal for the policy: whether a forward may
     * continue anyway (against the pod that replaced it) is the forwarder's decision, because only
     * the forwarder can retarget its session.
     */
    POD_GONE,
    BUDGET_EXHAUSTED,
    ATTEMPTS_EXHAUSTED
}

/**
 * A failed handshake: which attempt failed, when the series of failures started, and why this one
 * failed.
 *
 * The values only make sense together and always travel as a unit, which keeps
 * [ForwardRetryPolicy.decide] within the parameter budget of the code style.
 */
internal data class FailedHandshake(
    /** 1-based number of the attempt that just failed. */
    val attempt: Int,
    /**
     * Clock reading ([ForwardRetryPolicy]'s `nowNanos`) of the first failure of this series; the
     * retry budgets start here.
     */
    val firstFailureNanos: Long,
    /** What the handshake attempt failed with. */
    val cause: Throwable
)

/**
 * Decides whether a failed port-forward handshake may be retried.
 *
 * The behaviour is split by session phase, because the two phases have opposite requirements:
 * connect-time must fail fast (the wizard is waiting and a broken workspace must surface at once),
 * while an established session must outlive a transient outage (VPN drop, sleep) without the Guest
 * ever noticing. [established] selects the phase; [PodPortForwarder] reads it from the session.
 *
 * The budget starts at the first failed handshake, not at socket accept, so a slow handshake does
 * not consume the whole budget. The decision is logged here because the log line explains the
 * [RetryDecision] and both must stay in sync.
 *
 * The policy judges the pod an attempt used: [RetryDecision.POD_GONE] means that pod is gone, not
 * that the forward is over. When the workspace still has a running pod, [PodPortForwarder]
 * retargets to it and retries; with no replacement the forward ends, which is the connect-time
 * "pod is gone" behaviour.
 */
internal class ForwardRetryPolicy(
    /**
     * Reads the clock the budgets are measured against. Overridable so a test can put a series
     * past a budget without waiting for it.
     */
    private val nowNanos: () -> Long = System::nanoTime,
    /** Returns pod liveness state to decide whether a failed forward may be retried. */
    private val livenessProvider: suspend (V1Pod) -> PodLiveness
) {
    companion object {
        // budget math:
        // attempts land at ~0s, ~1.2s and ~2.4s (1s reconnect delay + 2x100ms client-gone probes between attempts),
        // so the 2s retry budget stops the loop after the 3rd attempt — a higher cap could never be reached.
        const val CONNECT_ATTEMPTS = 3
        const val RECONNECT_DELAY_MS: Long = 1000

        /**
         * Safety cap against a stuck socket, not the healing window: retries run only while the
         * Guest keeps re-dialing, so the Guest's own dial window bounds recovery from above.
         *
         * Measured from the first failure of the connection and preserved across pod retargets, so
         * a cluster that keeps replacing the pod cannot restart the wall on every replacement.
         */
        const val STEADY_STATE_OUTAGE_BUDGET_MS: Long = 10 * 60 * 1000L
        const val STEADY_STATE_MAX_DELAY_MS: Long = 5000

        private const val RETRY_BUDGET_MS = 2_000L

        /** Backoff of an established session: heals short blips without hammering the API server. */
        private val STEADY_STATE_BACKOFF_MS = longArrayOf(
            RECONNECT_DELAY_MS,
            2 * RECONNECT_DELAY_MS,
            3 * RECONNECT_DELAY_MS,
            STEADY_STATE_MAX_DELAY_MS
        )

        private const val CONNECT_PHASE = "connect-time"
        private const val STEADY_STATE_PHASE = "steady-state"
    }

    private val logger = logger<ForwardRetryPolicy>()

    /**
     * Suspending: it awaits the liveness provider (a bounded HTTP call on [kotlinx.coroutines.Dispatchers.IO]).
     */
    suspend fun decide(
        pod: V1Pod,
        ports: PortMapping,
        established: Boolean,
        failure: FailedHandshake
    ): RetryDecision {
        val liveness = livenessProvider(pod)
        val retry = evaluate(liveness, established, failure)
        logFailure(retry, liveness, ports, established, failure)
        return retry
    }

    /**
     * Delay before the next attempt in the given phase: connect-time keeps [RECONNECT_DELAY_MS],
     * an established session backs off from 1s to [STEADY_STATE_MAX_DELAY_MS].
     */
    fun reconnectDelayMs(attempt: Int, established: Boolean): Long {
        require(attempt >= 1) { "attempt is 1-based, was $attempt" }
        if (!established) return RECONNECT_DELAY_MS
        val index = minOf(attempt, STEADY_STATE_BACKOFF_MS.size) - 1
        return STEADY_STATE_BACKOFF_MS[index]
    }

    private fun evaluate(
        liveness: PodLiveness,
        established: Boolean,
        failure: FailedHandshake
    ): RetryDecision = when {
        // a gone pod serves no retry in either phase
        liveness == PodLiveness.GONE -> RetryDecision.POD_GONE
        // an established session has no attempt limit: only its outage budget ends the retries
        established -> if (isElapsed(failure.firstFailureNanos, STEADY_STATE_OUTAGE_BUDGET_MS)) {
            RetryDecision.BUDGET_EXHAUSTED
        } else {
            RetryDecision.RETRY
        }
        failure.attempt >= CONNECT_ATTEMPTS -> RetryDecision.ATTEMPTS_EXHAUSTED
        isElapsed(failure.firstFailureNanos, RETRY_BUDGET_MS) -> RetryDecision.BUDGET_EXHAUSTED
        else -> RetryDecision.RETRY
    }

    private fun isElapsed(firstFailureNanos: Long, budgetMs: Long): Boolean =
        nowNanos() - firstFailureNanos >= budgetMs * NANOS_PER_MILLI

    private fun logFailure(
        retry: RetryDecision,
        liveness: PodLiveness,
        ports: PortMapping,
        established: Boolean,
        failure: FailedHandshake
    ) {
        val (localPort, remotePort) = ports
        // both phases share this method, so the phase must be in every line: operators have to
        // tell "wizard fails fast" from "session outlived an outage" in the same log
        val phase = if (established) STEADY_STATE_PHASE else CONNECT_PHASE
        val context = "phase=$phase, podLiveness=$liveness"
        val cause = failure.cause
        val lastCause = "${cause.javaClass.simpleName}: ${cause.message}"
        when (retry) {
            RetryDecision.RETRY ->
                logger.info(
                    "Port forward handshake $localPort -> $remotePort failed on attempt ${failure.attempt}: " +
                            "${cause.message} ($context); retrying in " +
                            "${reconnectDelayMs(failure.attempt, established)}ms"
                )
            RetryDecision.POD_GONE ->
                logger.info(
                    "Port forward handshake $localPort -> $remotePort failed on attempt ${failure.attempt}: " +
                            "${cause.message} ($context); the pod is gone"
                )
            RetryDecision.ATTEMPTS_EXHAUSTED ->
                logger.warn(
                    "Port forward $localPort -> $remotePort failed after ${failure.attempt} attempt(s) " +
                            "($context, lastCause=$lastCause); giving up",
                    cause
                )
            RetryDecision.BUDGET_EXHAUSTED ->
                logger.warn(
                    "Port forward $localPort -> $remotePort failed after ${failure.attempt} attempt(s) " +
                            "($context, lastCause=$lastCause); giving up: " +
                            "${budgetName(established)} exhausted",
                    cause
                )
        }
    }

    private fun budgetName(established: Boolean): String =
        if (established) {
            "steady-state outage budget (${STEADY_STATE_OUTAGE_BUDGET_MS}ms)"
        } else {
            "connect-time retry budget (${RETRY_BUDGET_MS}ms)"
        }
}
