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
import com.redhat.devtools.gateway.openshift.FAST_FAIL_DIAL_MS
import com.redhat.devtools.gateway.openshift.ForwardDials
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Brings an open JetBrains Client back to its workspace **transparently**: the window is never
 * closed and never relaunched, the existing Guest is prodded to re-dial the plugin's port forward.
 *
 * The watcher covers the outage the plugin's own layers cannot heal: the forwarder keeps retrying
 * while the Guest keeps re-dialing, but once the Guest has given up, only a prod from the Gateway
 * side can make it try again. Recovery is read from the forward, never from the platform: a
 * successful prod only proves a message was queued, so the watcher waits for a real dial outcome on
 * the session ([ForwardDials]).
 *
 * **It resolves the pod workspace-scoped, not from the session.** The pod the forward was pinned to
 * is exactly the pod that may be gone after a pod roll, and nothing refreshes it: the forwarder's
 * own re-resolution needs an accepted connection, and in this outage the Guest has stopped dialing,
 * so no connection ever arrives. Probing the pinned pod would poll a corpse for the whole budget, so
 * every poll asks [Collaborators.resolvePod] for the pod that serves the workspace *now*.
 *
 * Lifecycle mirrors [com.redhat.devtools.gateway.devworkspace.DevWorkspaceSessionWatch]: an explicit
 * [start]/[stop] pair and an idempotent start, so the repeated `onRemoteEnd` signals of a flapping
 * session never spawn a second loop.
 *
 * The loop, per iteration (plan §4.2):
 * 1. the client is gone -> stop quietly; the Guest window no longer exists, nothing to recover
 * 2. the Guest holds an active connection -> recovered, nothing to fix
 * 3. no running+ready pod, or the probe fails -> poll again (cheap list, no prod)
 * 4. otherwise prod and wait one redial window:
 *    long-lived dial or a live handshake -> recovered
 *    a run of fast-failed dials -> the link looks stale, the next attempt refreshes it
 *    anything else -> next attempt prods again
 *
 * @param collaborators everything the loop touches, injected so it runs without a cluster.
 * @param timing intervals, the budget, and the clock/sleep seams.
 */
@Suppress("TooManyFunctions") // one recovery loop: each step is small and separately testable
internal class ThinClientRecoveryWatcher(
    private val collaborators: Collaborators,
    private val timing: Timing = Timing()
) {

    /**
     * The injected dependencies of the watcher.
     *
     * @param resolvePod the pod that currently serves the workspace
     *   ([com.redhat.devtools.gateway.openshift.DevWorkspacePods.findFirstRunning]).
     * @param probe opens and closes a real port forward to the IDE port: proves the whole path.
     * @param cachedLink the local join link the Guest originally dialed.
     * @param refreshLink a freshly built local join link, or `null` while none can be built yet.
     * @param prod prods the Guest to re-dial [cachedLink]: `updateJoinLink(link, forceReconnect)`.
     * @param session the dial outcomes of this session's forward.
     * @param isClientPresent whether the Guest window still exists.
     * @param onRecovered called once when the Guest re-dialed; the Guest repaints its status.
     * @param onRecoveryFailed called once when the budget is exhausted; never relaunches anything.
     */
    @Suppress("LongParameterList") // the watcher's seams: one object instead of a wide constructor
    internal class Collaborators(
        val resolvePod: () -> V1Pod?,
        val probe: suspend (V1Pod) -> Boolean,
        val cachedLink: URI,
        val refreshLink: suspend () -> URI?,
        val prod: suspend (URI) -> Unit,
        val session: ForwardDials,
        val isClientPresent: () -> Boolean,
        val onRecovered: () -> Unit,
        val onRecoveryFailed: () -> Unit
    )

    /**
     * Intervals, the recovery budget, and the clock/sleep seams.
     *
     * The seams exist so tests can shrink every interval and drive the clock instead of waiting out
     * a ten-minute budget; production uses the defaults.
     */
    @Suppress("LongParameterList") // one value per seam, kept together so the loop takes two objects
    internal class Timing(
        val pollIntervalMs: Long = 5_000,
        val longPollIntervalMs: Long = 15_000,
        val longPollAfterMs: Long = 120_000,
        val redialWaitMs: Long = 10_000,
        val budgetMs: Long = 600_000,
        val nowNanos: () -> Long = System::nanoTime,
        val sleep: suspend (Long) -> Unit = { delay(it) }
    )

    private companion object {
        /** Resampling of the in-memory dial outcomes while waiting for the Guest to re-dial. */
        const val OUTCOME_POLL_INTERVAL_MS: Long = 250

        /** A lone short-lived dial is not evidence of a stale link; a run of them is. */
        const val FAST_FAILED_DIALS_FOR_STALE: Long = 2

        const val MIN_SLEEP_MS: Long = 1
        const val NANOS_PER_MILLI: Long = 1_000_000
        const val MILLIS_PER_SECOND: Long = 1_000
    }

    private val active = AtomicBoolean(false)
    private var job: Job? = null

    /**
     * Whether the recovery loop is running. Teardown reads it *before* [stop] so a Guest closed
     * during recovery withholds the workspace auto-stop (§7.9).
     */
    val isActive: Boolean
        get() = active.get()

    /**
     * Starts the recovery loop, unless one is already running: `onRemoteEnd` fires once per lost
     * connection and may fire repeatedly while the session flaps.
     */
    fun start(scope: CoroutineScope): Job? {
        if (!active.compareAndSet(false, true)) {
            return job
        }
        return scope.launch { recoverUntilStopped() }
            .also { job = it }
    }

    /** Stops the loop promptly: its next suspension throws, so no further prod is sent. */
    fun stop() {
        active.set(false)
        job?.cancel()
        job = null
    }

    private suspend fun recoverUntilStopped() {
        val episode = Episode(timing.nowNanos())
        try {
            while (active.get() && !episode.isFinished && !isBudgetExhausted(episode)) {
                if (!collaborators.isClientPresent()) {
                    stopQuietly(episode, "the JetBrains Client is gone")
                    break
                }
                step(episode)
            }
            if (active.get() && !episode.isFinished) {
                reportFailure()
            }
        } finally {
            finish()
        }
    }

    /**
     * One loop iteration. Every failure inside it is logged and treated as a failed iteration: the
     * watcher keeps polling instead of giving up. Cancellation propagates, so [stop] is prompt.
     */
    @Suppress("TooGenericExceptionCaught") // an iteration failure means "retry on the next poll"
    private suspend fun step(episode: Episode) {
        try {
            iterate(episode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            thisLogger().warn("Connection recovery iteration failed; retrying on the next poll.", e)
            waitForNextPoll(episode)
        }
    }

    @Suppress("ReturnCount") // guard clauses: each early return is one terminal iteration outcome
    private suspend fun iterate(episode: Episode) {
        if (collaborators.session.hasActiveForward) {
            reportRecovered(episode, "the Guest holds an active connection")
            return
        }
        val pod = collaborators.resolvePod()
        if (pod == null || !collaborators.probe(pod)) {
            waitForNextPoll(episode)
            return
        }
        // probing takes seconds: the Guest may have re-dialed successfully in the meantime
        if (collaborators.session.hasActiveForward) {
            reportRecovered(episode, "the Guest holds an active connection")
            return
        }
        // the attempt and the dial count are committed only once a prod is actually delivered: a
        // slow link build (a stale link's status exec can outlast the whole redial window) must not
        // consume an attempt number or start the observation window early
        val delivery = deliverProd(episode)
        if (delivery == null) {
            waitForNextPoll(episode)
            return
        }
        when (awaitOutcome(delivery)) {
            DialOutcome.RECOVERED -> reportRecovered(episode, "the Guest re-dialed")
            DialOutcome.STALE -> staleLink(episode)
            DialOutcome.NOTHING -> thisLogger().debug(
                "No re-dial observed after prodding the Guest; prodding again."
            )
        }
    }

    /**
     * Builds the link and sends one prod.
     *
     * The attempt number counts *delivered* prods only, so the reconnect nonce never skips: a failed
     * delivery is retried as the same attempt.
     *
     * @return when the prod was delivered, the point the observation window starts at: the send
     *   timestamp and the fast-failed-dial count at that same moment; `null` when nothing was sent.
     */
    @Suppress("TooGenericExceptionCaught") // the seams throw whatever the platform throws
    private suspend fun deliverProd(episode: Episode): ProdDelivery? {
        val link = try {
            linkFor(episode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            thisLogger().warn("Could not build a join link to prod the JetBrains Client with.", e)
            return null
        }
        return try {
            collaborators.prod(link)
            episode.attempt++
            thisLogger().info("Prodded the JetBrains Client to reconnect (attempt ${episode.attempt}).")
            ProdDelivery(timing.nowNanos(), collaborators.session.fastFailedDials)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a dead handle must not crash the watcher: the loop's client-presence check decides
            thisLogger().warn("Could not prod the JetBrains Client to reconnect.", e)
            null
        }
    }

    /**
     * The link to prod with. The first attempt sends [Collaborators.cachedLink] as-is: it costs
     * nothing and is valid unless the pod or the IDE server changed. Later attempts add the
     * reconnect nonce so the Guest's same-link no-op guard cannot swallow the prod, and refresh the
     * link once the dials proved it stale (a 15 s status exec is never paid while polling).
     */
    private suspend fun linkFor(episode: Episode): URI {
        val attempt = episode.attempt + 1
        if (attempt <= 1) {
            return collaborators.cachedLink
        }
        val base = if (episode.mustRefreshLink) {
            collaborators.refreshLink() ?: collaborators.cachedLink
        } else {
            collaborators.cachedLink
        }
        return ThinClientJoinLink.withReconnectNonce(base, attempt.toLong())
    }

    /**
     * Waits one redial window for the Guest's dials to prove recovery, or to prove the link stale.
     *
     * The window starts at [ProdDelivery.sentAtNanos] — when the prod was actually delivered — so a
     * slow link build (a stale link's status exec) never eats it.
     *
     * A long-lived connection since the prod means the RD session was up. A handshake that succeeded
     * recently and whose connection has not ended yet is treated as alive: the Guest may still be
     * establishing the session, so cutting it with another prod would be wrong.
     */
    private suspend fun awaitOutcome(delivery: ProdDelivery): DialOutcome {
        val prodAtNanos = delivery.sentAtNanos
        val deadlineNanos = prodAtNanos + millisToNanos(timing.redialWaitMs)
        while (active.get() && !hasRecoveredSince(prodAtNanos) && timing.nowNanos() < deadlineNanos) {
            timing.sleep(outcomePollDelay(deadlineNanos))
        }
        val fastFailedSinceProd = collaborators.session.fastFailedDials - delivery.fastFailedAtProd
        return when {
            hasRecoveredSince(prodAtNanos) -> DialOutcome.RECOVERED
            fastFailedSinceProd >= FAST_FAILED_DIALS_FOR_STALE -> DialOutcome.STALE
            else -> DialOutcome.NOTHING
        }
    }

    /** Sleeps at most one poll interval, never past the redial deadline, never zero. */
    private fun outcomePollDelay(deadlineNanos: Long): Long {
        val remainingMs = (deadlineNanos - timing.nowNanos()) / NANOS_PER_MILLI
        return minOf(OUTCOME_POLL_INTERVAL_MS, maxOf(remainingMs, MIN_SLEEP_MS))
    }

    private fun hasRecoveredSince(prodAtNanos: Long): Boolean {
        if (collaborators.session.lastLongLivedDialNanos?.let { it > prodAtNanos } == true) {
            return true
        }
        return hasOpenConnectionSince(prodAtNanos)
    }

    /**
     * Whether a handshake succeeded since [referenceNanos], recently enough to count as alive, and
     * the connection it belongs to has not already ended as a fast-failed dial.
     */
    private fun hasOpenConnectionSince(referenceNanos: Long): Boolean {
        val session = collaborators.session
        val handshakeNanos = session.lastSuccessfulHandshakeNanos
            ?.takeIf { it > referenceNanos }
            ?: return false
        val endedFast = session.lastFastFailedDialNanos?.let { it > handshakeNanos } == true
        return !endedFast && timing.nowNanos() - handshakeNanos < millisToNanos(FAST_FAIL_DIAL_MS)
    }

    private suspend fun waitForNextPoll(episode: Episode) {
        timing.sleep(pollIntervalAt(episode))
    }

    /** Cheap polls while the outage is young; a long outage is polled less often. */
    private fun pollIntervalAt(episode: Episode): Long =
        if (elapsedMs(episode) < timing.longPollAfterMs) {
            timing.pollIntervalMs
        } else {
            timing.longPollIntervalMs
        }

    private fun isBudgetExhausted(episode: Episode): Boolean =
        elapsedMs(episode) >= timing.budgetMs

    private fun elapsedMs(episode: Episode): Long =
        (timing.nowNanos() - episode.startedAtNanos) / NANOS_PER_MILLI

    private fun millisToNanos(millis: Long): Long = millis * NANOS_PER_MILLI

    /**
     * Every dial since the prod ended fast: the pod or the IDE server the link points at is gone, so
     * later attempts refresh the link. The flag stays set: the refresh is retried until it yields a
     * link, because a stale link must not be prodded forever.
     */
    private fun staleLink(episode: Episode) {
        episode.mustRefreshLink = true
        thisLogger().info(
            "Only fast-failed dials since the prod: the join link for port " +
                "${collaborators.cachedLink.port} looks stale; refreshing it on the next attempt."
        )
    }

    private fun reportRecovered(episode: Episode, reason: String) {
        episode.isFinished = true
        thisLogger().info("Connection recovery succeeded: $reason.")
        notify(collaborators.onRecovered, "report the recovered connection")
    }

    private fun reportFailure() {
        thisLogger().warn(
            "Connection recovery failed: the workspace did not come back within " +
                "${timing.budgetMs / MILLIS_PER_SECOND} s."
        )
        notify(collaborators.onRecoveryFailed, "report the failed recovery")
    }

    private fun stopQuietly(episode: Episode, reason: String) {
        episode.isFinished = true
        thisLogger().info("Stopped connection recovery: $reason.")
    }

    /** A callback must never fail the loop: the recovery outcome is already decided by then. */
    private inline fun notify(callback: () -> Unit, action: String) {
        runCatching(callback)
            .onFailure { e -> thisLogger().warn("Could not $action.", e) }
    }

    private fun finish() {
        active.set(false)
    }

    /**
     * Progress of one recovery episode: the delivered prod attempts so far, whether the dials made
     * the link look stale, and whether the episode already ended.
     */
    private class Episode(val startedAtNanos: Long) {
        var attempt: Int = 0
        var mustRefreshLink: Boolean = false
        var isFinished: Boolean = false
    }

    /**
     * What the loop knows once a prod was actually delivered: the start of the observation window and
     * the fast-failed-dial count at that moment. Dial outcomes seen before it belong to the previous
     * attempt, not to the one being measured.
     */
    private class ProdDelivery(val sentAtNanos: Long, val fastFailedAtProd: Long)

    /** What the Guest's dials said after a prod. */
    private enum class DialOutcome {
        /** The Guest re-dialed: a connection came up (or was up long enough to matter). */
        RECOVERED,

        /** Only fast-failed dials: the link the Guest was prodded with looks stale. */
        STALE,

        /** No dial at all: prod again (the Guest may have ignored the message). */
        NOTHING
    }
}
