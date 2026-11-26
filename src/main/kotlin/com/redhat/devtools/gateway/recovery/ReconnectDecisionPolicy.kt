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

import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.openshift.DIAL_CONFIRMATION_MS
import com.redhat.devtools.gateway.openshift.ForwardDials

/**
 * Decides whether client's own seamless reconnect loop can still recover.
 *
 * Forced reconnect = `updateJoinLink(forceReconnect = true)`: project disposed + re-opened → window
 * closes, new frame (own loop preserves frame, so let it succeed while link is valid).
 * CRW-13910 live: doomed dial ended while newer seamless dial was connecting → watcher forced
 * reconnect → cancelled the dial about to work.
 *
 * Follows the *dial cycle*, not one dial:
 * - dial in flight (accepted socket or unconfirmed handshake) → wait
 * - survives confirmation threshold → recovered, no forced reconnect, frame kept
 * - ends before confirming → count failed dial, keep sampling
 * - run of failed dials → loop runs but cannot succeed (stale link): force reconnect, refresh link.
 *   Outranks a dial in flight: client re-dials before previous doomed dial ends → waiting for an end
 *   would never release; only episode budget would
 * - no dial activity for a redial window → client gave up: force reconnect with cached link
 *
 * Always terminates: failed-dial run bounds churn, idle window bounds silence, episode budget both.
 *
 * Decides only, never mutates the episode. Caller applies verdict: report recovery, refresh link on
 * [Verdict.RECONNECT_REBUILT], stop quietly on [Verdict.CLIENT_GONE].
 */
internal class ReconnectDecisionPolicy(
    /** Dial outcomes of this session's forward. */
    private val session: ForwardDials,
    /** Client window still exists? */
    private val isClientPresent: () -> Boolean,
    /** Intervals, budget, clock/sleep seams of the watcher. */
    private val timing: ThinClientRecoveryWatcher.Timing,
    /** Recovery loop still running? A stop ends the decision at once. */
    private val isRecoveryActive: () -> Boolean,
    /** Episode started at given [System.nanoTime] spent its budget? */
    private val isBudgetExhausted: (startedAtNanos: Long) -> Boolean,
    /** Client-silence window before next forced reconnect, from episode's silence escalation. */
    private val effectiveRedialWaitMs: (consecutiveNothing: Int) -> Long
) {
    companion object {
        /** Resample in-memory dial outcomes while waiting for re-dial. */
        const val OUTCOME_POLL_INTERVAL_MS: Long = 250

        /** Lone failed dial ≠ stale link; a run is. */
        const val FAILED_DIALS_FOR_STALE: Long = 2

        /** Every nth sample logged (~5 s at 250 ms poll). */
        const val LOG_EVERY_SAMPLES: Int = 20

        const val NANOS_PER_MILLI: Long = 1_000_000
    }

    /**
     * One decision's outcome: verdict + proving handshake for [Verdict.RECOVERED]. Evidence travels
     * with verdict → policy stays stateless, caller need not re-read (dial may have invalidated it).
     *
     * @param evidenceNanos confirmed handshake for [Verdict.RECOVERED]; `null` otherwise.
     */
    data class Outcome(val verdict: Verdict, val evidenceNanos: Long? = null)

    /**
     * Oldest handshake that outlived [DIAL_CONFIRMATION_MS]; `null` if none.
     *
     * Open connection alone confirms nothing — stale-link dial is copied until client's own transport
     * timeout, then dropped — so only age confirms a session.
     */
    fun confirmedHandshakeNanos(): Long? =
        session.oldestActiveHandshakeNanos
            ?.takeIf { timing.nowNanos() - it >= millisToNanos(DIAL_CONFIRMATION_MS) }

    /**
     * Decides client's outcome from its dials, sampling until conclusive.
     *
     * @param startedAtNanos episode start the budget is measured from.
     * @param consecutiveNothing episode's forced reconnects that yielded no dial; escalates window.
     * @return outcome for caller; [Verdict.STOPPED] when loop was stopped.
     */
    suspend fun decide(startedAtNanos: Long, consecutiveNothing: Int): Outcome {
        val dialRun = DialRun(startedAtNanos, session.failedDials, consecutiveNothing)
        return pollUntilDecided(dialRun)
            ?: Outcome(Verdict.STOPPED)
    }

    /** Samples the dials until one sample yields an outcome, or the loop stops. */
    private suspend fun pollUntilDecided(dialRun: DialRun): Outcome? {
        var outcome: Outcome? = null
        while (outcome == null && isRecoveryActive()) {
            outcome = sampleAndDecide(dialRun)
            if (outcome == null) timing.sleep(OUTCOME_POLL_INTERVAL_MS)
        }
        return outcome
    }

    /** Takes one sample: the terminal evidence first, then the current dial state. */
    private fun sampleAndDecide(dialRun: DialRun): Outcome? {
        terminalOutcome(dialRun.startedAtNanos)
            ?.let { return it }
        dialRun.recordSample()
        return dialStateOutcome(dialRun)
    }

    /** The outcome on the evidence sampled at the start of each poll, or `null` to keep deciding. */
    private fun terminalOutcome(startedAtNanos: Long): Outcome? {
        if (!isClientPresent()) return Outcome(Verdict.CLIENT_GONE)
        val confirmedAtNanos = confirmedHandshakeNanos()
        return when {
            confirmedAtNanos != null ->
                Outcome(Verdict.RECOVERED, confirmedAtNanos)

            isBudgetExhausted(startedAtNanos) -> {
                // dial churn keeps snapshot changing → idle window alone never ends the decision; budget bounds it too
                thisLogger().info(
                    "Connection recovery budget exhausted while deciding on the client's dials."
                )
                Outcome(Verdict.BUDGET_EXHAUSTED)
            }

            else ->
                null
        }
    }

    /**
     * Outcome for current dial state, or `null` to keep deciding.
     *
     * Failed-dial run outranks a dial in flight: overlapping doomed dials never leave a gap —
     * CRW-13910 live: seamless loop re-dials every ~5 s, each doomed dial lives ~6.5 s (client's 6 s
     * transport timeout) → a dial always in flight, waiting for an end would wait forever.
     *
     * Safe: a failed dial is a handshook dial dropped within [DIAL_CONFIRMATION_MS], by either side —
     * the stale-link signature. The restarted IDE server ends a stale-token dial itself (pod side),
     * the Guest's transport timeout ends it client side; excluding either would leave a same-pod
     * server restart without stale-link evidence (CRW-13910 live regression 2026-11-26). A dial
     * queued through an outage is not counted (no handshake, or a stalled one), so a network cut's
     * dials keep their protection. Working link → RD session lives minutes, so two dropped dials
     * prove every dial with cached link is doomed, incl. in flight.
     * Forcing costs nothing (dial dies at transport timeout anyway) and its end changes nothing.
     *
     * Network cut keeps protection: dials never handshake → `failedDials` flat → in-flight guard
     * holds the forced reconnect while client still tries. Once no dial in flight, plain silence for
     * a redial window = client gave up.
     */
    private fun dialStateOutcome(dialRun: DialRun): Outcome? = when {
        dialRun.failedDialsDelta >= FAILED_DIALS_FOR_STALE -> Outcome(Verdict.RECONNECT_REBUILT)

        dialRun.snapshot.isInFlight -> null

        timing.nowNanos() - dialRun.lastActivityNanos >=
            millisToNanos(effectiveRedialWaitMs(dialRun.consecutiveNothing)) ->
            Outcome(Verdict.RECONNECT_CACHED)

        else -> null
    }

    /**
     * Mutable state of one dial run: dial snapshot, when it last changed, episode's forced reconnects
     * that began it. Activity = any snapshot change → a still-trying client never looks like a
     * giving-up one.
     */
    private inner class DialRun(
        val startedAtNanos: Long,
        private val failedDialsAtStart: Long,
        val consecutiveNothing: Int
    ) {
        var snapshot: DialSnapshot = dialSnapshot()
            private set
        var lastActivityNanos: Long = timing.nowNanos()
            private set
        private var samples: Int = 0

        /** Failed dials since this dial run began. */
        val failedDialsDelta: Long
            get() = snapshot.failedDials - failedDialsAtStart

        /** Resamples the dials and logs every [LOG_EVERY_SAMPLES]th sample. */
        fun recordSample() {
            val current = dialSnapshot()
            if (current != snapshot) {
                snapshot = current
                lastActivityNanos = timing.nowNanos()
            }
            if (++samples % LOG_EVERY_SAMPLES == 0) {
                logDialRunSample(current, failedDialsAtStart, lastActivityNanos)
            }
        }
    }

    /**
     * What silent sampling sees: dial in flight, session report, time since last change. Debug only.
     */
    private fun logDialRunSample(
        snapshot: DialSnapshot,
        failedDialsAtStart: Long,
        lastActivityNanos: Long
    ) {
        thisLogger().debug(
            "Reconnect decision: inFlight=${snapshot.isInFlight}, " +
                "isClientDialed=${snapshot.isClientDialed}, " +
                "oldestActiveHandshake=${snapshot.oldestActiveHandshakeNanos != null}, " +
                "failedDials=${snapshot.failedDials} " +
                "(delta ${snapshot.failedDials - failedDialsAtStart}), " +
                "lastActivityAgeMs=${(timing.nowNanos() - lastActivityNanos) / NANOS_PER_MILLI}"
        )
    }

    /** Dial state sampled by the decision: in flight from socket accept to end. */
    private fun dialSnapshot(): DialSnapshot = DialSnapshot(
        isClientDialed = session.isClientDialed,
        oldestActiveHandshakeNanos = session.oldestActiveHandshakeNanos,
        failedDials = session.failedDials
    )

    private fun millisToNanos(millis: Long): Long = millis * NANOS_PER_MILLI

    /**
     * Client's dial state at one sample. Any change = activity → still-trying client never mistaken
     * for a giving-up one.
     */
    private data class DialSnapshot(
        val isClientDialed: Boolean,
        val oldestActiveHandshakeNanos: Long?,
        val failedDials: Long
    ) {
        /** Dial open: accepted socket or handshake not yet confirmed. */
        val isInFlight: Boolean
            get() = isClientDialed
                    || oldestActiveHandshakeNanos != null
    }

    /** How the decision ended. */
    enum class Verdict {
        /** Dial confirmed session: report recovered, no forced reconnect. */
        RECOVERED,

        /** Client's own loop cannot recover: force reconnect with cached link. */
        RECONNECT_CACHED,

        /** Failed-dial run proved link stale: force reconnect with rebuilt link only. */
        RECONNECT_REBUILT,

        /** Recovery loop stopped: no reconnect, no report. */
        STOPPED,

        /** Client window gone: stop quietly, no reconnect, no report. */
        CLIENT_GONE,

        /** Episode budget ended the decision; outer loop reports the failure. */
        BUDGET_EXHAUSTED
    }
}
