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
    POD_GONE,
    BUDGET_EXHAUSTED,
    ATTEMPTS_EXHAUSTED
}

/**
 * Decides whether a failed port-forward handshake may be retried.
 *
 * The budget starts at the first failed handshake, not at socket accept, so a slow handshake does
 * not consume the whole budget. The decision is logged here because the log line explains the
 * [RetryDecision] and both must stay in sync.
 */
internal class ForwardRetryPolicy(
    /** Returns pod liveness state to decide whether a failed forward may be retried. */
    private val livenessProvider: suspend (V1Pod) -> PodLiveness
) {
    companion object {
        // budget math:
        // attempts land at ~0s, ~1.2s and ~2.4s (1s reconnect delay + 2x100ms client-gone probes between attempts),
        // so the 2s retry budget stops the loop after the 3rd attempt — a higher cap could never be reached.
        const val CONNECT_ATTEMPTS = 3
        const val RECONNECT_DELAY_MS: Long = 1000
        private const val RETRY_BUDGET_MS = 2_000L
    }

    private val logger = logger<ForwardRetryPolicy>()

    /**
     * Suspending: it awaits the liveness provider (a bounded HTTP call on [kotlinx.coroutines.Dispatchers.IO]).
     */
    suspend fun decide(
        pod: V1Pod,
        firstFailureNanos: Long,
        attempt: Int,
        ports: PortMapping,
        cause: Throwable
    ): RetryDecision {
        val liveness = livenessProvider(pod)
        val retry = when {
            liveness == PodLiveness.GONE ->
                RetryDecision.POD_GONE
            attempt >= CONNECT_ATTEMPTS ->
                RetryDecision.ATTEMPTS_EXHAUSTED
            System.nanoTime() - firstFailureNanos >= RETRY_BUDGET_MS * NANOS_PER_MILLI ->
                RetryDecision.BUDGET_EXHAUSTED
            else ->
                RetryDecision.RETRY
        }
        logFailure(retry, liveness, attempt, ports, cause)
        return retry
    }

    private fun logFailure(
        retry: RetryDecision,
        liveness: PodLiveness,
        attempt: Int,
        ports: PortMapping,
        cause: Throwable
    ) {
        val (localPort, remotePort) = ports
        when (retry) {
            RetryDecision.RETRY ->
                logger.info(
                    "Port forward handshake $localPort -> $remotePort failed on attempt $attempt: ${cause.message} " +
                            "(podLiveness=$liveness); retrying in ${RECONNECT_DELAY_MS}ms"
                )
            RetryDecision.POD_GONE ->
                logger.info(
                    "Port forward handshake $localPort -> $remotePort failed on attempt $attempt: ${cause.message} " +
                            "(podLiveness=$liveness); not retrying"
                )
            else ->
                logger.warn(
                    "Port forward $localPort -> $remotePort failed after $attempt attempt(s) " +
                            "(podLiveness=$liveness, " +
                            "lastCause=${cause.javaClass.simpleName}: ${cause.message}); giving up",
                    cause
                )
        }
    }
}
