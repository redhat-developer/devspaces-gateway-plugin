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
import com.redhat.devtools.gateway.openshift.describePod
import com.redhat.devtools.gateway.openshift.isSamePod
import com.redhat.devtools.gateway.util.callerFrames
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
 * successful prod only proves a message was queued, so the watcher waits for a dial outcome on the
 * session ([ForwardDials]).
 *
 * **What counts as recovery.** Only a dial that outlived [DIAL_CONFIRMATION_MS] does. An open TCP
 * copy or a long-lived dial proves nothing on its own: CRW-13910 live testing measured stale-link
 * dials copied for 6.5–6.9 s before the Guest's own transport-creation timeout dropped them (6 s),
 * and those dials never carried an RD session.
 *
 * **A replaced pod makes the cached link stale by definition.** A new pod is a new idea-server
 * process with a new session token, so the `jt`/`fp` the Guest dials are unknown to it and every
 * dial with the cached link is doomed however long it is copied. The watcher detects the roll by pod
 * identity ([Collaborators.sessionPod] vs. the pod that serves the workspace now), not by guessing
 * from dial lifetimes.
 *
 * **A prod is a project restart, so it is the last resort.** `updateJoinLink(forceReconnect =
 * true)` makes the Guest dispose the project and re-open it: state survives server-side, but the
 * window is replaced by a new frame. The Guest's own seamless loop preserves the frame, so the
 * watcher lets it finish a dial in flight and only prods once that loop is proven unable to recover.
 *
 * **A link known stale is never prodded.** Such a prod cannot succeed - the session token is
 * unknown to the new idea-server - and each one makes the Guest re-open its project against a dead
 * session, burning its project-open retries: CRW-13910 live testing saw the Guest close itself
 * (exitCode 178) after two prods carrying a stale link. Without a rebuilt link the watcher waits.
 *
 * **It resolves the pod workspace-scoped, not from the session.** The pod the forward was pinned to
 * is exactly the pod that may be gone after a roll, and nothing refreshes it: the forwarder's own
 * re-resolution needs an accepted connection, and in this outage the Guest has stopped dialing, so
 * no connection ever arrives. Probing the pinned pod would poll a corpse for the whole budget, so
 * every poll asks [Collaborators.resolvePod] for the pod that serves the workspace *now*.
 *
 * Lifecycle mirrors [com.redhat.devtools.gateway.devworkspace.DevWorkspaceSessionWatch]: an explicit
 * [start]/[stop] pair and an idempotent start, so the repeated `onRemoteEnd` signals of a flapping
 * session never spawn a second loop.
 *
 * The loop, per iteration (plan §4.2):
 * 1. the client is gone -> stop quietly; the Guest window no longer exists, nothing to recover
 * 2. a connection older than the confirmation threshold -> recovered, the session is established
 * 3. no running+ready pod, or the probe fails -> poll again (cheap list, no prod)
 * 4. the workspace pod was replaced -> the cached link is stale: refresh it and prod at once
 * 5. the Guest's own seamless reconnection loop is observed first: a dial in flight is awaited (a
 *    run of failed dials outranks it - overlapping doomed dials never leave a gap), a confirmed one
 *    is recovery (the frame is preserved); a run of failed dials or a silent redial window means
 *    that loop cannot recover, and only then is the Guest prodded - a prod restarts the project,
 *    which the user sees as the window being replaced
 * 6. the prod starts an observation window:
 *    a confirmed dial -> recovered
 *    a run of failed dials -> the link looks stale, the next attempt refreshes it
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
     * @param sessionPod the pod that link was built against: a different pod means the link is stale.
     * @param refreshLink a freshly built local join link, or `null` while none can be built yet.
     * @param prod prods the Guest to re-dial [cachedLink]: `updateJoinLink(link, forceReconnect)`.
     * @param session the dial outcomes of this session's forward.
     * @param isClientPresent whether the Guest window still exists.
     * @param onRecovered called once per confirmed recovery; the Guest repaints its status.
     * @param onRecoveryFailed called once when the budget is exhausted; never relaunches anything.
     */
    @Suppress("LongParameterList") // the watcher's seams: one object instead of a wide constructor
    internal class Collaborators(
        val resolvePod: () -> V1Pod?,
        val probe: suspend (V1Pod) -> Boolean,
        val cachedLink: URI,
        val sessionPod: V1Pod?,
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

        /** A lone failed dial is not evidence of a stale link; a run of them is. */
        const val FAILED_DIALS_FOR_STALE: Long = 2

        /** Every nth observation sample is logged (~5 s at the 250 ms poll). */
        const val OBSERVATION_LOG_EVERY_SAMPLES: Int = 20

        const val MIN_SLEEP_MS: Long = 1
        const val NANOS_PER_MILLI: Long = 1_000_000
        const val MILLIS_PER_SECOND: Long = 1_000
    }

    private val active = AtomicBoolean(false)
    private var job: Job? = null

    /**
     * Evidence of the session last reported as recovered: the handshake of the connection that
     * confirmed it, or the end of the long-lived dial that did.
     *
     * `onRemoteEnd` fires per connection, so while the Guest's dial churn settles several episodes
     * can observe the same established session. Each would otherwise pull the window to the front
     * and log again; one recovery per session is enough.
     */
    @Volatile
    private var lastReportedEvidence: Long? = null

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
        // who cancelled the loop is not visible from the loop's own death log alone
        thisLogger().info("Stopping connection recovery; caller=${callerFrames(javaClass.name)}")
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
            thisLogger().info(
                "Connection recovery loop ended: active=${active.get()}, " +
                    "finished=${episode.isFinished}, attempts=${episode.attempt}, " +
                    "budgetExhausted=${isBudgetExhausted(episode)}"
            )
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
        if (reportIfConnectionConfirmed(episode)) {
            return
        }
        val pod = collaborators.resolvePod()
        if (pod != null) {
            detectPodRoll(episode, pod)
        }
        if (pod == null || !collaborators.probe(pod)) {
            waitForNextPoll(episode)
            return
        }
        // Give the Guest's own seamless reconnection loop its chance before prodding: a prod is a
        // project-level restart that swaps the window, so it is only worth paying when that loop
        // cannot succeed. A dial carrying a stale link is not worth waiting for.
        if (!skipsObservation(episode) && observeGuestRecovery(episode) != Observation.PROD) {
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
            DialOutcome.RECOVERED ->
                reportRecovered(episode, recoveryEvidence(), "a dial confirmed the session")
            DialOutcome.STALE -> staleLink(episode)
            DialOutcome.NOTHING -> thisLogger().debug(
                "No re-dial observed after prodding the Guest; prodding again."
            )
        }
    }

    /**
     * Reports recovery when the Guest holds a connection that outlived [DIAL_CONFIRMATION_MS].
     *
     * An open connection alone is not evidence - a stale-link dial is copied for the Guest's own
     * transport timeout and then dropped - so only its age confirms a session.
     *
     * @return whether recovery was reported, ending the episode.
     */
    private fun reportIfConnectionConfirmed(episode: Episode): Boolean {
        val handshakeNanos = collaborators.session.oldestActiveHandshakeNanos
            ?.takeIf { timing.nowNanos() - it >= millisToNanos(DIAL_CONFIRMATION_MS) }
            ?: return false
        thisLogger().info(
            "The Guest's connection on port ${collaborators.cachedLink.port} outlived a stale " +
                "dial; the session is established."
        )
        reportRecovered(episode, handshakeNanos, "a connection outlived a stale dial")
        return true
    }

    /**
     * Declares the cached link stale when the workspace's pod is no longer the one it was built
     * against: a replacement pod is a new idea-server with a new session token, so the link the
     * Guest keeps dialing cannot work, however long the Guest's dials are copied.
     */
    private fun detectPodRoll(episode: Episode, resolvedPod: V1Pod) {
        val sessionPod = collaborators.sessionPod ?: return
        if (episode.podRolled || isSamePod(sessionPod, resolvedPod)) {
            return
        }
        episode.podRolled = true
        episode.mustRefreshLink = true
        thisLogger().info(
            "The workspace pod was replaced (session pod ${describePod(sessionPod)}, serving " +
                "${describePod(resolvedPod)}); the cached join link is stale and will be refreshed."
        )
    }

    /**
     * Whether waiting for the Guest's open connection is pointless.
     *
     * After a pod roll the Guest's current dial still carries the cached link, which the new
     * idea-server cannot accept: it can never confirm, so waiting would only delay the fix. Once a
     * prod has delivered a refreshed link, the Guest's dials are legitimate again and worth waiting
     * for.
     */
    /**
     * Whether observing the Guest's own reconnection loop is pointless.
     *
     * After a pod roll the Guest's dials carry the cached link, whose session token the new
     * idea-server does not know: they can never confirm, and the frame swap of a prod is inherent
     * because the backend session is new. Once a prod has delivered a refreshed link the dials are
     * legitimate again and worth observing.
     */
    private fun skipsObservation(episode: Episode): Boolean =
        episode.podRolled && !episode.sentRefreshedLink

    /**
     * Gives the Guest's own seamless reconnection loop the chance to recover before prodding.
     *
     * A prod is `updateJoinLink(forceReconnect = true)`: the Guest disposes the project and re-opens
     * it, which the user sees as the window closing and a new frame opening. Its own loop preserves
     * the frame, so it must be allowed to succeed whenever the link is still valid. CRW-13910 live
     * testing caught exactly the opposite: a doomed dial ended while a newer seamless dial was
     * connecting, the watcher prodded, and the prod cancelled the dial that was about to work.
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
     */
    private suspend fun observeGuestRecovery(episode: Episode): Observation {
        val observationStartNanos = timing.nowNanos()
        val failedDialsAtStart = collaborators.session.failedDials
        var lastActivityNanos = observationStartNanos
        var previous = dialSnapshot()
        var outcome: Observation? = null
        var samples = 0

        while (outcome == null && active.get()) {
            when {
                !collaborators.isClientPresent() -> {
                    stopQuietly(episode, "the JetBrains Client is gone")
                    outcome = Observation.STOPPED
                }

                reportIfConnectionConfirmed(episode) -> outcome = Observation.RECOVERED

                else -> {
                    val snapshot = dialSnapshot()
                    if (snapshot != previous) {
                        previous = snapshot
                        lastActivityNanos = timing.nowNanos()
                    }
                    if (++samples % OBSERVATION_LOG_EVERY_SAMPLES == 0) {
                        logObservationSample(snapshot, failedDialsAtStart, lastActivityNanos)
                    }
                    outcome = decideObservation(episode, snapshot, failedDialsAtStart, lastActivityNanos)
                    if (outcome == null) {
                        timing.sleep(OUTCOME_POLL_INTERVAL_MS)
                    }
                }
            }
        }
        return outcome ?: Observation.STOPPED
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
    private fun decideObservation(
        episode: Episode,
        snapshot: DialSnapshot,
        failedDialsAtStart: Long,
        lastActivityNanos: Long
    ): Observation? = when {
        snapshot.failedDials - failedDialsAtStart >= FAILED_DIALS_FOR_STALE -> {
            staleLink(episode)
            Observation.PROD
        }

        snapshot.isInFlight -> null

        timing.nowNanos() - lastActivityNanos >= millisToNanos(timing.redialWaitMs) ->
            Observation.PROD

        else -> null
    }

    /** The dial state the observation samples: a dial is in flight from its socket to its end. */
    private fun dialSnapshot(): DialSnapshot = DialSnapshot(
        isClientDialed = collaborators.session.isClientDialed,
        oldestActiveHandshakeNanos = collaborators.session.oldestActiveHandshakeNanos,
        failedDials = collaborators.session.failedDials
    )

    /**
     * Builds the link and sends one prod.
     *
     * The attempt number counts *delivered* prods only, so the reconnect nonce never skips: a failed
     * delivery is retried as the same attempt.
     *
     * @return when the prod was delivered, the point the observation window starts at: the send
     *   timestamp and the failed-dial count at that same moment; `null` when nothing was sent.
     */
    @Suppress("TooGenericExceptionCaught") // the seams throw whatever the platform throws
    private suspend fun deliverProd(episode: Episode): ProdDelivery? {
        // null link = nothing may be prodded yet; the cached link is never used as a substitute
        val link = prodLinkOrNull(episode) ?: return null
        return try {
            collaborators.prod(link.uri)
            episode.attempt++
            if (link.isRefreshed) {
                episode.sentRefreshedLink = true
            }
            thisLogger().info("Prodded the JetBrains Client to reconnect (attempt ${episode.attempt}).")
            ProdDelivery(timing.nowNanos(), collaborators.session.failedDials)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a dead handle must not crash the watcher: the loop's client-presence check decides
            thisLogger().warn("Could not prod the JetBrains Client to reconnect.", e)
            null
        }
    }

    /** [linkFor], with a failing link build treated as "nothing to send yet". */
    @Suppress("TooGenericExceptionCaught") // the seams throw whatever the platform throws
    private suspend fun prodLinkOrNull(episode: Episode): ProdLink? = try {
        linkFor(episode)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        thisLogger().warn("Could not build a join link to prod the JetBrains Client with.", e)
        null
    }

    /**
     * The link to prod with, or `null` when the Guest must not be prodded yet.
     *
     * A valid link (the first prod of a non-stale episode) is sent as-is: it costs nothing. A link
     * known stale - a replaced pod, or only failed dials - is only ever sent rebuilt: prodding the
     * dead one cannot succeed, and every prod makes the Guest dispose and re-open its project
     * against a dead session, which burns its project-open retries (CRW-13910 live testing: the
     * Guest closed itself after two such prods, exitCode 178). While no fresh link exists yet the
     * answer is therefore `null` - no prod, keep polling.
     *
     * The reconnect nonce is added from the second prod on, where the Guest may already have seen
     * the previous link.
     */
    private suspend fun linkFor(episode: Episode): ProdLink? {
        val attempt = episode.attempt + 1
        val link = if (episode.mustRefreshLink) {
            freshLinkOrWait(episode)
        } else {
            ProdLink(collaborators.cachedLink, isRefreshed = false)
        }
        return link?.let { ProdLink(withNonceIfNeeded(it.uri, attempt), it.isRefreshed) }
    }

    /**
     * A rebuilt link, or `null` while the IDE server cannot report one yet.
     *
     * The wait is logged once per stretch, not on every poll: the watcher keeps calling this until
     * the link exists, so a per-call log would flood the one period a live test needs to read.
     */
    private suspend fun freshLinkOrWait(episode: Episode): ProdLink? {
        val refreshed = collaborators.refreshLink()
        if (refreshed != null) {
            episode.reportedMissingFreshLink = false
            return ProdLink(refreshed, isRefreshed = true)
        }
        if (!episode.reportedMissingFreshLink) {
            episode.reportedMissingFreshLink = true
            thisLogger().info(
                "The join link is stale and no fresh link is available yet; waiting for the IDE " +
                    "server to report one."
            )
        }
        return null
    }

    /** Defeats the Guest's same-link no-op guard from the second prod of an episode on. */
    private fun withNonceIfNeeded(link: URI, attempt: Int): URI =
        if (attempt == 1) link else ThinClientJoinLink.withReconnectNonce(link, attempt.toLong())

    /**
     * Waits one redial window for the Guest's dials to prove recovery, or to prove the link stale.
     *
     * The window starts at [ProdDelivery.sentAtNanos] — when the prod was actually delivered — so a
     * slow link build (a stale link's status exec) never eats it.
     *
     * A confirmed dial since the prod means the session came up. A run of failed dials means the
     * Guest kept dialing the same dead link.
     */
    private suspend fun awaitOutcome(delivery: ProdDelivery): DialOutcome {
        val prodAtNanos = delivery.sentAtNanos
        val deadlineNanos = prodAtNanos + millisToNanos(timing.redialWaitMs)
        while (active.get() && !hasConfirmedDialSince(prodAtNanos) && timing.nowNanos() < deadlineNanos) {
            timing.sleep(outcomePollDelay(deadlineNanos))
        }
        val failedSinceProd = collaborators.session.failedDials - delivery.failedDialsAtProd
        return when {
            hasConfirmedDialSince(prodAtNanos) -> DialOutcome.RECOVERED
            failedSinceProd >= FAILED_DIALS_FOR_STALE -> DialOutcome.STALE
            else -> DialOutcome.NOTHING
        }
    }

    /** Sleeps at most one poll interval, never past the redial deadline, never zero. */
    private fun outcomePollDelay(deadlineNanos: Long): Long {
        val remainingMs = (deadlineNanos - timing.nowNanos()) / NANOS_PER_MILLI
        return minOf(OUTCOME_POLL_INTERVAL_MS, maxOf(remainingMs, MIN_SLEEP_MS))
    }

    /**
     * Whether a dial confirmed a session since [referenceNanos]: a long-lived dial that ended after
     * it, or a connection still open whose handshake has outlived the confirmation threshold.
     */
    private fun hasConfirmedDialSince(referenceNanos: Long): Boolean {
        val longLivedDialEnded =
            collaborators.session.lastLongLivedDialNanos?.let { it > referenceNanos } == true
        val oldestHandshakeNanos = collaborators.session.oldestActiveHandshakeNanos
        val openConnectionConfirmed = oldestHandshakeNanos != null &&
            timing.nowNanos() - oldestHandshakeNanos >= millisToNanos(DIAL_CONFIRMATION_MS)
        return longLivedDialEnded || openConnectionConfirmed
    }

    /**
     * The evidence that identifies the session a recovery report refers to: the live connection's
     * handshake, or the end of the long-lived dial that proved it.
     */
    private fun recoveryEvidence(): Long? =
        collaborators.session.oldestActiveHandshakeNanos
            ?: collaborators.session.lastLongLivedDialNanos

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
     * Every dial since the prod ended before it could confirm a session: the pod or the IDE server
     * the link points at is gone, so later attempts refresh the link. The flag stays set: the
     * refresh is retried until it yields a link, because a stale link must not be prodded forever.
     */
    private fun staleLink(episode: Episode) {
        episode.mustRefreshLink = true
        thisLogger().info(
            "Only failed dials since the prod: the join link for port " +
                "${collaborators.cachedLink.port} looks stale; refreshing it on the next attempt."
        )
    }

    /**
     * Reports a recovered session once per confirmed session.
     *
     * `onRemoteEnd` fires per connection, so several episodes can observe the same established
     * session while the Guest's dial churn settles; each would otherwise pull the window to the
     * front and log again.
     *
     * @param evidence the timestamp identifying the confirmed session ([recoveryEvidence]), or
     *   `null` when it cannot be named; two reports with the same evidence are one recovery.
     */
    private fun reportRecovered(episode: Episode, evidence: Long?, reason: String) {
        episode.isFinished = true
        if (evidence != null && evidence == lastReportedEvidence) {
            thisLogger().info("Connection recovery already reported for this session.")
            return
        }
        lastReportedEvidence = evidence
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
     * Progress of one recovery episode: the delivered prod attempts so far, whether the cached link
     * is stale (dials proved it, or the pod was replaced), and whether the episode already ended.
     */
    private class Episode(val startedAtNanos: Long) {
        var attempt: Int = 0
        var mustRefreshLink: Boolean = false
        var podRolled: Boolean = false

        /** Whether a prod has carried a rebuilt link: only then may the Guest's dials be awaited. */
        var sentRefreshedLink: Boolean = false

        /** Whether the "stale but no fresh link yet" wait of this episode has been logged. */
        var reportedMissingFreshLink: Boolean = false
        var isFinished: Boolean = false
    }

    /**
     * What the loop knows once a prod was actually delivered: the start of the observation window
     * and the failed-dial count at that moment. Dial outcomes seen before it belong to the previous
     * attempt, not to the one being measured.
     */
    private class ProdDelivery(val sentAtNanos: Long, val failedDialsAtProd: Long)

    /**
     * A link to prod with, and whether it was rebuilt. Only a rebuilt link can unblock a workspace
     * whose pod was replaced, which is what re-enables observing the Guest's own dials.
     */
    private class ProdLink(val uri: URI, val isRefreshed: Boolean)

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

    /** How the pre-prod observation ended. */
    private enum class Observation {
        /** A dial confirmed a session: reported as recovered, no prod was sent. */
        RECOVERED,

        /** The Guest's own loop cannot recover: prod. */
        PROD,

        /** The loop was stopped, or the client is gone: no prod, no report. */
        STOPPED
    }

    /** What the Guest's dials said after a prod. */
    private enum class DialOutcome {
        /** A dial confirmed an established session. */
        RECOVERED,

        /** Only failed dials: the link the Guest was prodded with looks stale. */
        STALE,

        /** No dial at all: prod again (the Guest may have ignored the message). */
        NOTHING
    }
}
