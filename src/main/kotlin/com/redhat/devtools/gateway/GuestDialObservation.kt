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

import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.openshift.DIAL_CONFIRMATION_MS
import com.redhat.devtools.gateway.openshift.ForwardDials

/**
 * Observes the Guest's own seamless reconnection loop and decides whether it can still recover.
 *
 * A prod is `updateJoinLink(forceReconnect = true)`: the Guest disposes the project and re-opens it,
 * which the user sees as the window closing and a new frame opening. Its own loop preserves the
 * frame, so it must be allowed to succeed whenever the link is still valid. CRW-13910 live testing
 * caught exactly the opposite: a doomed dial ended while a newer seamless dial was connecting, the
 * watcher prodded, and the prod cancelled the dial that was about to work.
 *
 * The observation therefore follows the *dial cycle*, not one dial:
 * - a dial in flight (accepted socket or unconfirmed handshake) -> wait for it;
 * - it survives the confirmation threshold -> recovered, no prod, frame preserved;
 * - it ends before confirming -> count the failed dial and keep observing;
 * - a run of failed dials -> the loop runs but cannot succeed (stale link): prod, refreshing
 *   the link. This outranks a dial in flight: the Guest re-dials before the previous doomed dial
 *   ends, so waiting for a dial to end would never release while the Guest keeps overlapping
 *   its dials - only the episode budget would end the wait;
 * - no dial activity at all for a redial window -> the Guest gave up: prod the cached link.
 *
 * It always terminates: the failed-dial run bounds the churn case, the idle window bounds the
 * silent case, and the episode budget bounds both.
 *
 * It is a pure decision maker: it never mutates the episode it observes for. The caller applies the
 * consequences of the verdict - reporting recovery, refreshing the link on
 * [Verdict.PROD_STALE], stopping quietly on [Verdict.CLIENT_GONE].
 */
internal class GuestDialObservation(
    /** Dial outcomes of this session's forward. */
    private val session: ForwardDials,
    /** Whether the Guest window still exists. */
    private val isClientPresent: () -> Boolean,
    /** Intervals, the budget, and the clock/sleep seams of the watcher. */
    private val timing: ThinClientRecoveryWatcher.Timing,
    /** Whether the recovery loop is still running: a stop ends the observation at once. */
    private val isRecoveryActive: () -> Boolean,
    /** Whether the episode that started at the given [System.nanoTime] spent its budget. */
    private val isBudgetExhausted: (startedAtNanos: Long) -> Boolean,
    /** The Guest-silence window before the next prod, from the episode's silence escalation. */
    private val effectiveRedialWaitMs: (consecutiveNothing: Int) -> Long
) {
    companion object {
        /** Resampling of the in-memory dial outcomes while waiting for the Guest to re-dial. */
        const val OUTCOME_POLL_INTERVAL_MS: Long = 250

        /** A lone failed dial is not evidence of a stale link; a run of them is. */
        const val FAILED_DIALS_FOR_STALE: Long = 2

        /** Every nth observation sample is logged (~5 s at the 250 ms poll). */
        const val OBSERVATION_LOG_EVERY_SAMPLES: Int = 20

        const val NANOS_PER_MILLI: Long = 1_000_000
    }

    /**
     * The handshake of the last observation that returned [Verdict.RECOVERED]: it identifies the
     * session the caller reports, so the caller never has to re-read evidence the dial may have
     * lost in the meantime.
     */
    var recoveredEvidenceNanos: Long? = null
        private set

    /**
     * The handshake of a connection that outlived [DIAL_CONFIRMATION_MS], or `null` when no
     * connection did.
     *
     * An open connection alone is not evidence - a stale-link dial is copied for the Guest's own
     * transport timeout and then dropped - so only its age confirms a session.
     */
    fun confirmationEvidence(): Long? =
        session.oldestActiveHandshakeNanos
            ?.takeIf { timing.nowNanos() - it >= millisToNanos(DIAL_CONFIRMATION_MS) }

    /**
     * Observes the Guest's dials until one of them decides the outcome.
     *
     * @param startedAtNanos start of the episode the budget is measured from.
     * @param consecutiveNothing prods of that episode that yielded no dial; escalates the window.
     * @return the verdict for the caller; [Verdict.STOPPED] when the loop was stopped.
     */
    suspend fun observe(startedAtNanos: Long, consecutiveNothing: Int): Verdict {
        recoveredEvidenceNanos = null
        val observationStartNanos = timing.nowNanos()
        val failedDialsAtStart = session.failedDials
        var lastActivityNanos = observationStartNanos
        var previous = dialSnapshot()
        var outcome: Verdict? = null
        var samples = 0

        while (outcome == null && isRecoveryActive()) {
            val confirmedHandshakeNanos = confirmationEvidence()
            when {
                !isClientPresent() -> outcome = Verdict.CLIENT_GONE

                confirmedHandshakeNanos != null -> {
                    recoveredEvidenceNanos = confirmedHandshakeNanos
                    outcome = Verdict.RECOVERED
                }

                isBudgetExhausted(startedAtNanos) -> {
                    // the dial churn keeps the snapshot changing, so the idle window alone would
                    // never let the observation end: the episode budget must bound it too
                    thisLogger().info(
                        "Connection recovery budget exhausted while observing the Guest's dials."
                    )
                    outcome = Verdict.BUDGET_EXHAUSTED
                }

                else -> {
                    val snapshot = dialSnapshot()
                    if (snapshot != previous) {
                        previous = snapshot
                        lastActivityNanos = timing.nowNanos()
                    }
                    if (++samples % OBSERVATION_LOG_EVERY_SAMPLES == 0) {
                        logObservationSample(snapshot, failedDialsAtStart, lastActivityNanos)
                    }
                    outcome = decide(
                        snapshot, failedDialsAtStart, lastActivityNanos, consecutiveNothing
                    )
                    if (outcome == null) {
                        timing.sleep(OUTCOME_POLL_INTERVAL_MS)
                    }
                }
            }
        }
        return outcome ?: Verdict.STOPPED
    }

    /**
     * The observation's verdict for the current dial state, or `null` to keep observing.
     *
     * A run of failed dials outranks a dial in flight, because overlapping doomed dials never leave
     * a gap: CRW-13910 live measurement saw the Guest's seamless loop re-dial every ~5 s while each
     * doomed dial lived ~6.5 s (the Guest's own 6 s transport timeout), so a dial was always in
     * flight and waiting for one to end would wait forever.
     *
     * That is safe because a failed dial is only counted when its forward handshake was delivered
     * and the Guest still dropped it within the confirmation threshold
     * ([DIAL_CONFIRMATION_MS]); a dial that never handshook - a network cut - is not counted. With a
     * working link the RD session establishes and the connection lives for minutes, so two failed
     * dials prove every dial carrying the cached link is doomed, including the one in flight.
     * Prodding it costs nothing (it dies at its transport timeout anyway) and its end would change
     * nothing.
     *
     * A network cut keeps its protection: its dials never handshake, so `failedDials` stays flat and
     * the in-flight guard below holds the prod while the Guest is still trying. Once no dial is in
     * flight, plain silence for a redial window means the Guest gave up.
     */
    private fun decide(
        snapshot: DialSnapshot,
        failedDialsAtStart: Long,
        lastActivityNanos: Long,
        consecutiveNothing: Int
    ): Verdict? = when {
        snapshot.failedDials - failedDialsAtStart >= FAILED_DIALS_FOR_STALE -> Verdict.PROD_STALE

        snapshot.isInFlight -> null

        timing.nowNanos() - lastActivityNanos >=
            millisToNanos(effectiveRedialWaitMs(consecutiveNothing)) -> Verdict.PROD

        else -> null
    }

    /**
     * States what the silent observation phase currently sees: whether a dial is in flight, what
     * the session reports, and how long nothing has changed. Debug only.
     */
    private fun logObservationSample(
        snapshot: DialSnapshot,
        failedDialsAtStart: Long,
        lastActivityNanos: Long
    ) {
        thisLogger().debug(
            "Recovery observation: inFlight=${snapshot.isInFlight}, " +
                "isClientDialed=${snapshot.isClientDialed}, " +
                "oldestActiveHandshake=${snapshot.oldestActiveHandshakeNanos != null}, " +
                "failedDials=${snapshot.failedDials} " +
                "(delta ${snapshot.failedDials - failedDialsAtStart}), " +
                "lastActivityAgeMs=${(timing.nowNanos() - lastActivityNanos) / NANOS_PER_MILLI}"
        )
    }

    /** The dial state the observation samples: a dial is in flight from its socket to its end. */
    private fun dialSnapshot(): DialSnapshot = DialSnapshot(
        isClientDialed = session.isClientDialed,
        oldestActiveHandshakeNanos = session.oldestActiveHandshakeNanos,
        failedDials = session.failedDials
    )

    private fun millisToNanos(millis: Long): Long = millis * NANOS_PER_MILLI

    /**
     * The Guest's dial state at one sample of the observation. Any change counts as activity, so a
     * Guest that is still trying is never mistaken for one that gave up.
     */
    private data class DialSnapshot(
        val isClientDialed: Boolean,
        val oldestActiveHandshakeNanos: Long?,
        val failedDials: Long
    ) {
        /** A dial is open (accepted socket, or a handshake that has not confirmed yet). */
        val isInFlight: Boolean
            get() = isClientDialed || oldestActiveHandshakeNanos != null
    }

    /** How the observation ended. */
    enum class Verdict {
        /** A dial confirmed a session: report it recovered, no prod is sent. */
        RECOVERED,

        /** The Guest's own loop cannot recover: prod the cached link. */
        PROD,

        /** A run of failed dials proved the link stale: prod only a rebuilt link. */
        PROD_STALE,

        /** The recovery loop was stopped: no prod, no report. */
        STOPPED,

        /** The Guest window no longer exists: stop quietly, no prod, no report. */
        CLIENT_GONE,

        /** The episode budget ended the observation; the outer loop reports the failure. */
        BUDGET_EXHAUSTED
    }
}
