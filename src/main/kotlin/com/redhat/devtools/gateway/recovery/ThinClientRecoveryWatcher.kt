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
import com.redhat.devtools.gateway.openshift.describePod
import com.redhat.devtools.gateway.openshift.isSamePod
import com.redhat.devtools.gateway.util.callerFrames
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Brings an open JetBrains Client back to its workspace **transparently**: window never closed, never
 * relaunched. Unchanged link re-dialed in place ([RecoveryEnvironment.reconnectInPlace]); only a
 * rebuilt one forces project re-open ([RecoveryEnvironment.forceReconnect]).
 *
 * Covers the outage the plugin's own layers cannot heal: forwarder keeps retrying while client keeps
 * re-dialing, but once client gave up, only a Gateway-side reconnect request makes it try again.
 * Recovery read from the forward, never from the platform: a successful request only proves a message
 * was queued → watcher waits for a dial outcome on the session ([ForwardDials]).
 *
 * **Recovery = survival.** Only a dial that outlived [DIAL_CONFIRMATION_MS]. Open TCP copy or
 * long-lived dial proves nothing: CRW-13910 live — stale-link dials copied 6.5–6.9 s before client's
 * 6 s transport-creation timeout dropped them, never carried an RD session.
 *
 * **Replaced pod ⇒ cached link stale by definition.** New pod = new idea-server + new session token →
 * dialed `jt`/`fp` unknown to it → every dial with cached link doomed however long copied. Roll
 * detected by pod identity ([RecoveryEnvironment.sessionPod] vs. pod serving now), not by guessing from
 * dial lifetimes. [ReconnectLinkPolicy] owns what may be sent per link state.
 *
 * **Forced reconnect = project restart = last resort.** `updateJoinLink(forceReconnect = true)` →
 * client disposes + re-opens project: state survives server-side, window replaced by new frame.
 * Unchanged link never needs it: `notifyReconnect()` re-dials last stored link in place, frame stays.
 * Client's own seamless loop also preserves frame → watcher lets a dial in flight finish
 * ([ReconnectDecisionPolicy]), reconnects only once that loop is proven unable.
 *
 * **Stale link never used for a reconnect.** Cannot succeed (token unknown to new idea-server) and
 * each one re-opens project against a dead session, burning project-open retries: CRW-13910 live —
 * client closed itself (exitCode 178) after two forced reconnects with a stale link. Without a
 * rebuilt link the watcher waits.
 *
 * **Pod resolved workspace-scoped, not from session.** Forward's pinned pod is exactly the one that
 * may be gone after a roll, nothing refreshes it: forwarder's re-resolution needs an accepted
 * connection, and client stopped dialing → none arrives. Probing the pinned pod = polling a corpse for
 * the whole budget → every poll asks [RecoveryEnvironment.resolvePod] for the pod serving *now*.
 *
 * Lifecycle mirrors [com.redhat.devtools.gateway.devworkspace.DevWorkspaceSessionWatch]: explicit
 * [start]/[stop], idempotent start → repeated `onRemoteEnd` of a flapping session never spawns a
 * second loop. A run ending on budget exhaustion is the session's last: client dial churn never
 * stops, a restarted run could only report the same failure again.
 *
 * Loop, per iteration (plan §4.2):
 * 1. client gone → stop quietly
 * 2. connection older than confirmation threshold → recovered
 * 3. no running+ready pod, or probe fails → poll again (cheap list, no reconnect)
 * 4. workspace pod replaced → cached link stale: refresh + force reconnect at once
 * 5. consult client's own seamless loop ([ReconnectDecisionPolicy]): dial in flight awaited (failed-dial
 *    run outranks it), confirmed one = recovery; run of failed dials or silent redial window = loop
 *    cannot recover, only then reconnect: unchanged link in place, stale one rebuilt
 * 6. reconnect request opens observation window:
 *    failed-dial run → link looks stale, next attempt refreshes
 *    dial but no outcome yet → silence escalation resets; later poll confirms
 *    no dial at all → next attempt reconnects again
 *
 * **Session survives the episode.** Confirmed recovery → watcher adopts delivered refreshed link + pod
 * that served it → later outage starts from what currently works instead of re-sending connect-time
 * link or mistaking the already-replaced pod for a new roll. Later forced delivery of an adopted link
 * carries a nonce (Guest already saw that link text); reconnect in place needs none (not an identical
 * `UpdateLink` message).
 *
 * @param environment everything the loop touches, injected to run without a cluster.
 * @param timing intervals, budget, clock/sleep seams.
 */
@Suppress("TooManyFunctions") // one recovery loop: each step is small and separately testable
internal class ThinClientRecoveryWatcher(
    private val environment: RecoveryEnvironment,
    private val timing: Timing = Timing()
) {

    /**
     * Environment the loop works against: this session's pod, forward, link, client. Injected to run
     * without a cluster.
     *
     * @param resolvePod pod currently serving the workspace
     *   ([com.redhat.devtools.gateway.openshift.DevWorkspacePods.findServingPod]).
     * @param probe opens+closes a real port forward to the IDE port: proves the whole path.
     * @param cachedLink local join link client originally dialed; watcher adopts a refreshed one once
     *   recovery confirms it.
     * @param sessionPod pod that link was built against: different pod ⇒ stale link. Advanced after a
     *   confirmed pod roll.
     * @param refreshLink freshly built local join link, or `null` while none can be built.
     * @param reconnectInPlace Guest re-dials last stored link without disposing project: `notifyReconnect()`.
     *   Transparent lever for an unchanged link.
     * @param forceReconnect forces client to re-dial given link: `updateJoinLink(link, forceReconnect)`.
     *   Disposes + re-opens project → only when link really changed.
     * @param session dial outcomes of this session's forward.
     * @param isClientPresent client window still exists?
     * @param onRecovered called once per confirmed recovery; client repaints its status.
     * @param onRecoveryFailed called once on budget exhaustion; never relaunches anything.
     */
    @Suppress("LongParameterList") // the watcher's seams: one object instead of a wide constructor
    internal class RecoveryEnvironment(
        val resolvePod: () -> V1Pod?,
        val probe: suspend (V1Pod) -> Boolean,
        val cachedLink: URI,
        val sessionPod: V1Pod?,
        val refreshLink: suspend () -> URI?,
        val reconnectInPlace: suspend () -> Unit,
        val forceReconnect: suspend (URI) -> Unit,
        val session: ForwardDials,
        val isClientPresent: () -> Boolean,
        val onRecovered: () -> Unit,
        val onRecoveryFailed: () -> Unit
    )

    /**
     * Intervals, recovery budget, clock/sleep seams.
     *
     * Seams let tests shrink every interval and drive the clock instead of waiting out a ten-minute
     * budget; production uses defaults.
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
        const val MIN_SLEEP_MS: Long = 1
        const val NANOS_PER_MILLI: Long = 1_000_000
        const val MILLIS_PER_SECOND: Long = 1_000

        /** Cap of the escalating client-silence window between forced reconnects that yielded nothing. */
        const val MAX_REDIAL_WAIT_MS: Long = 60_000

        /** Keeps escalation shift below doubling overflow. */
        const val MAX_BACKOFF_SHIFT: Int = 6

        /**
         * Why a session is reported recovered when a dial outlived [DIAL_CONFIRMATION_MS]: fast
         * pre-check and decision's recovered verdict observe the same evidence.
         */
        const val CONFIRMED_SESSION_REASON: String = "a connection outlived a stale dial"
    }

    private val active = AtomicBoolean(false)

    /** Guards publishing and detaching [activeOwner], [active] and [job] as one state transition. */
    private val stateLock = Any()

    /**
     * Run that currently owns [active]; only that run may release it.
     *
     * A superseded run finishes after the replacement started (cancellation is not synchronous):
     * without ownership its [releaseOwnership] would clear the replacement's latch and silently end the new
     * episode before it recovered anything.
     */
    private var activeOwner: Any? = null

    /**
     * Latched when a run ended on budget exhaustion: recovery is over for this session, a later
     * `onRemoteEnd` must not restart a ten-minute run.
     *
     * Client dial churn keeps ending connections after the failure was reported → without this latch
     * the watcher restarted every time and reported the same failure forever (CRW-13910 live: second
     * run began 9 s after the first failed at 600 s). Recovery, client disappearing and [stop] do not
     * set it: they leave recovery pending or moot, not impossible. No reset needed — owner creates one
     * watcher per connect.
     */
    private val hasFailedDefinitively = AtomicBoolean(false)

    private var job: Job? = null

    /**
     * Evidence of session last reported recovered: confirming connection's handshake, or end of the
     * long-lived dial that did.
     *
     * `onRemoteEnd` fires per connection → while dial churn settles several runs can observe the same
     * session, each pulling the window to front and logging again; one recovery per session enough.
     */
    @Volatile
    private var lastReportedEvidence: Long? = null

    /** Decides which link this run's forced reconnect carries. */
    private val linkPolicy = ReconnectLinkPolicy(environment.cachedLink, environment.refreshLink)

    /**
     * Pod the current join link was built against. Starts with connect-time pod, advanced by
     * [adoptSessionState] when a recovery proves a replacement pod serves the session.
     */
    private var sessionPod: V1Pod? = environment.sessionPod

    /**
     * Decides whether client's own reconnection loop can recover before a forced reconnect; see
     * [ReconnectDecisionPolicy].
     */
    private val reconnectDecision = ReconnectDecisionPolicy(
        session = environment.session,
        isClientPresent = environment.isClientPresent,
        timing = timing,
        isRecoveryActive = { active.get() },
        isBudgetExhausted = ::isBudgetExhausted,
        effectiveRedialWaitMs = ::effectiveRedialWaitMs
    )

    /**
     * Recovery run active?
     *
     * Reports the start/stop latch, not coroutine state: true as soon as [start] commits, stays true
     * through the loop's own teardown, regardless of [Job] lifecycle. Latch is what [start]'s
     * compare-and-set reads to keep one run per outage → only state a caller may use for "is recovery
     * active".
     *
     * Teardown reads it *before* [stop] so a client closed during recovery withholds the workspace
     * auto-stop (§7.9).
     */
    fun isActive(): Boolean = active.get()

    /**
     * Starts a recovery run unless one runs already or an earlier one failed definitively.
     *
     * `onRemoteEnd` fires once per lost connection, repeatedly while session flaps → only the first
     * signal of an outage may spawn a loop; once budget was spent, session cannot recover → every
     * later signal refused.
     *
     * @return true iff *this* call began a new run; false when a loop is already running, and false
     *   forever once the run ended on budget exhaustion.
     */
    fun start(scope: CoroutineScope): Boolean = synchronized(stateLock) {
        when {
            hasFailedDefinitively.get() || active.get() ->
                false

            !scope.isActive ->
                // owner's scope already cancelled: launching would never run the loop, so no
                // episode may be published
                false

            else -> {
                val owner = Any()
                // LAZY lets owner, latch and job be published before the loop can observe them
                val startedJob = scope.launch(start = CoroutineStart.LAZY) {
                    recoverUntilStopped(owner)
                }
                activeOwner = owner
                active.set(true)
                job = startedJob
                // Cancellation can prevent the loop body from ever running, so its finally never
                // releases the latch: completion is the one signal that always arrives.
                startedJob.invokeOnCompletion { releaseOwnership(owner) }
                startedJob.start()
                true
            }
        }
    }

    /** Stops loop promptly: next suspension throws → no further forced reconnect sent. */
    fun stop() {
        // who cancelled the loop is not visible from the loop's own death log alone
        thisLogger().info("Stopping connection recovery; caller=${callerFrames(javaClass.name)}")
        val detachedJob: Job?
        synchronized(stateLock) {
            // release ownership first: the cancelled run's finish must not clear a replacement's latch
            activeOwner = null
            active.set(false)
            detachedJob = job
            job = null
        }
        // cancel only the job this stop detached: a replacement started since must keep running
        detachedJob?.cancel()
    }

    /**
     * Runs one recovery run to its end, polling once per [Timing] interval.
     *
     * Steps while active, unfinished, within budget; each iteration ([step]) chooses between waiting,
     * forcing a reconnect and reporting recovery. Client window checked here, ahead of [step]: a
     * closed window ends recovery unconditionally, whatever dial/pod evidence says.
     *
     * Three distinct exits:
     * - **stopped** ([stop] or owner's scope): [active] false, nothing reported, run only released —
     *   recovery still pending, a later `onRemoteEnd` may retry.
     * - **finished** ([RecoveryRun.isFinished]): a step already reported recovery or quiet stop →
     *   budget check must not overwrite the verdict.
     * - **budget exhausted**: no step finished it while [active] still true → session unrecoverable.
     *   Latched ([hasFailedDefinitively]) so endless dial churn cannot restart the same ten-minute run;
     *   failure reported once.
     *
     * [finally] always releases the latch it owns ([releaseOwnership]), even on cancellation or exception
     * escaping [step] → watcher never stays "active" without a running loop.
     *
     * @param owner identifies this run: only it may release [active], never a superseded run that
     *   finishes after this one started.
     */
    private suspend fun recoverUntilStopped(owner: Any) {
        val recoveryRun = RecoveryRun(timing.nowNanos())
        try {
            while (active.get()
                && !recoveryRun.isFinished
                && !isBudgetExhausted(recoveryRun.startedAtNanos)) {
                if (!environment.isClientPresent()) {
                    stopQuietly(recoveryRun, "the JetBrains Client is gone")
                    break
                }
                step(recoveryRun)
            }
            if (active.get()
                && !recoveryRun.isFinished) {
                // budget, not a stop, ended this run: recovery impossible for this session → no
                // later onRemoteEnd may start another
                hasFailedDefinitively.set(true)
                reportFailure()
            }
        } finally {
            thisLogger().info(
                "Connection recovery loop ended: active=${active.get()}, " +
                    "finished=${recoveryRun.isFinished}, attempts=${recoveryRun.attempt}, " +
                    "budgetExhausted=${isBudgetExhausted(recoveryRun.startedAtNanos)}"
            )
            releaseOwnership(owner)
        }
    }

    /**
     * One loop iteration. Failures inside are logged and treated as a failed iteration: watcher keeps
     * polling instead of giving up. Cancellation propagates → [stop] is prompt.
     */
    @Suppress("TooGenericExceptionCaught") // an iteration failure means "retry on the next poll"
    private suspend fun step(recoveryRun: RecoveryRun) {
        try {
            recoverOnce(recoveryRun)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            thisLogger().warn("Connection recovery iteration failed; retrying on the next poll.", e)
            waitForNextPoll(recoveryRun)
        }
    }

    /**
     * Checks the outage once, takes at most one recovery action, in the plan's order: confirmed
     * handshake ends the run; replaced pod marks the cached link stale for the next forced reconnect;
     * missing/unprobeable pod only polls again (cheap list, reconnect cannot land); then client's own
     * seamless loop gets its chance before a forced reconnect is delivered and its dial outcome awaited.
     *
     * An iteration that does nothing waits for the next poll here, not in the loop; early returns are
     * terminal iteration outcomes, not failures.
     */
    @Suppress("ReturnCount") // guard clauses: each early return is one terminal iteration outcome
    private suspend fun recoverOnce(recoveryRun: RecoveryRun) {
        val connectedNanos = reconnectDecision.confirmedHandshakeNanos()
        if (connectedNanos != null) {
            reportConnectionConfirmed(recoveryRun, connectedNanos)
            return
        }
        val pod = environment.resolvePod()
        recoveryRun.resolvedPod = pod
        if (pod != null) {
            requestLinkRefreshIfPodReplaced(recoveryRun, pod)
        }
        if (pod == null
            || !environment.probe(pod)) {
            waitForNextPoll(recoveryRun)
            return
        }
        // Give client's own seamless loop its chance before forcing a reconnect: forced reconnect =
        // project-level restart swapping the window, only worth paying when that loop cannot succeed.
        // A dial carrying a stale link is not worth waiting for.
        if (!skipsReconnectDecision(recoveryRun)
            && !reconnectAllowed(recoveryRun)) {
            return
        }
        // attempt + dial count commit only once a forced reconnect is actually delivered: a slow link
        // build (stale link's status exec can outlast the whole redial window) must not consume an
        // attempt number or start the observation window early
        val delivery = deliverReconnect(recoveryRun)
        if (delivery == null) {
            waitForNextPoll(recoveryRun)
            return
        }
        when (awaitOutcome(delivery)) {
            DialOutcome.STALE ->
                staleLink(recoveryRun)

            DialOutcome.DIALED -> {
                // Guest answered: a later poll confirms once the dial outlives DIAL_CONFIRMATION_MS
                // → silence window must not escalate
                recoveryRun.consecutiveNothing = 0
                thisLogger().debug(
                    "The client dialed after the forced reconnect but the session has not confirmed " +
                        "yet; waiting for the next poll."
                )
            }

            DialOutcome.NOTHING -> {
                recoveryRun.consecutiveNothing++
                thisLogger().debug(
                    "No re-dial observed after forcing the client to reconnect; forcing again after a " +
                        "${effectiveRedialWaitMs(recoveryRun.consecutiveNothing)} ms silence window."
                )
            }
        }
    }

    /**
     * Runs the reconnect decision and applies its verdict: recovery reported here, a stale link
     * forces the next reconnect to refresh, a silent client is stopped.
     *
     * @return whether a forced reconnect may follow.
     */
    private suspend fun reconnectAllowed(recoveryRun: RecoveryRun): Boolean {
        val decision = reconnectDecision.decide(recoveryRun.startedAtNanos, recoveryRun.consecutiveNothing)
        return when (decision.verdict) {
            ReconnectDecisionPolicy.Verdict.RECONNECT_CACHED ->
                true

            ReconnectDecisionPolicy.Verdict.RECONNECT_REBUILT -> {
                staleLink(recoveryRun)
                true
            }

            ReconnectDecisionPolicy.Verdict.RECOVERED -> {
                reportConfirmedSession(recoveryRun, decision.evidenceNanos)
                false
            }

            ReconnectDecisionPolicy.Verdict.CLIENT_GONE -> {
                stopQuietly(recoveryRun, "the JetBrains Client is gone")
                false
            }

            ReconnectDecisionPolicy.Verdict.STOPPED,
            ReconnectDecisionPolicy.Verdict.BUDGET_EXHAUSTED ->
                false
        }
    }

    /**
     * Reports the established session a confirmed handshake proves, ending the run.
     *
     * Caller owns the confirmation query → pure reporting: receives the handshake that outlived
     * [DIAL_CONFIRMATION_MS], never re-reads dials.
     *
     * @param handshakeNanos confirmed handshake identifying the session.
     */
    private fun reportConnectionConfirmed(recoveryRun: RecoveryRun, handshakeNanos: Long) {
        thisLogger().info(
            "The client's connection on port ${environment.cachedLink.port} outlived a stale " +
                "dial; the session is established."
        )
        reportConfirmedSession(recoveryRun, handshakeNanos)
    }

    /**
     * Declares the cached link stale when the workspace pod is no longer the one it was built against:
     * replacement pod = new idea-server + new session token → the dialed link cannot work, however long
     * client dials are copied.
     */
    private fun requestLinkRefreshIfPodReplaced(recoveryRun: RecoveryRun, resolvedPod: V1Pod) {
        val linkPod = sessionPod
            ?: return
        if (recoveryRun.hasPodRolled
            || isSamePod(linkPod, resolvedPod)) {
            return
        }
        recoveryRun.hasPodRolled = true
        recoveryRun.linkState = recoveryRun.linkState.requestingRefresh()
        thisLogger().info(
            "The workspace pod was replaced (session pod ${describePod(linkPod)}, serving " +
                "${describePod(resolvedPod)}); the cached join link is stale and will be refreshed."
        )
    }

    /**
     * Whether observing client's own reconnection loop is pointless.
     *
     * After a pod roll client's dials carry the cached link, whose session token the new idea-server
     * does not know → they can never confirm, and the forced reconnect's frame swap is inherent (new
     * backend session). Once a forced reconnect delivered a refreshed link, dials are legitimate again
     * and worth observing.
     */
    private fun skipsReconnectDecision(recoveryRun: RecoveryRun): Boolean =
        recoveryRun.hasPodRolled && !recoveryRun.linkState.hasDeliveredRefreshed

    /**
     * Client-silence window before the next forced reconnect. Doubles per forced reconnect that yielded
     * no dial: each is a project dispose/reopen → hammering it every redial window can make the client
     * close itself (CRW-13910 live, exitCode 178). Any dial outcome resets the escalation.
     */
    private fun effectiveRedialWaitMs(consecutiveNothing: Int): Long {
        val factor = 1L shl minOf(consecutiveNothing, MAX_BACKOFF_SHIFT)
        return minOf(timing.redialWaitMs * factor, MAX_REDIAL_WAIT_MS)
    }

    /**
     * Builds the link and sends one reconnect request if the budget still allows it.
     *
     * @return when delivered, the observation window's start: send timestamp + failed-dial count at
     *   that moment; `null` when nothing was sent (no link yet, or the budget expired while building
     *   the link).
     */
    private suspend fun deliverReconnect(recoveryRun: RecoveryRun): ReconnectDelivery? {
        // null link = nothing may be sent yet; cached link never used as a substitute
        val link = reconnectLinkOrNull(recoveryRun) ?: return null
        return if (isBudgetExhausted(recoveryRun.startedAtNanos)) {
            // a slow link build can outlast the whole budget: forcing the Guest to reconnect now
            // would swap its window right before the loop reports failure
            thisLogger().debug(
                "The recovery budget expired while building the join link; sending nothing."
            )
            null
        } else {
            sendReconnect(recoveryRun, link)
        }
    }

    /**
     * Sends one reconnect request for [link].
     *
     * Attempt counts *delivered* requests only → nonce never skips: a failed delivery retried as the
     * same attempt.
     *
     * Rebuilt link → [RecoveryEnvironment.forceReconnect]: Guest must dispose project to adopt a link
     * whose server session changed. Unchanged link → [RecoveryEnvironment.reconnectInPlace]: re-dial
     * in place, window and frame kept. Forcing an unchanged link = project restart for nothing: that
     * turned a five-minute network cut into a visible frame swap.
     */
    @Suppress("TooGenericExceptionCaught") // the seams throw whatever the platform throws
    private suspend fun sendReconnect(
        recoveryRun: RecoveryRun,
        link: ReconnectLink
    ): ReconnectDelivery? = try {
        // capture before the reconnect: baseline must be deterministic — failed dials that ended
        // before this delivery belong to the previous attempt. A dial this reconnect itself kills
        // ends after the snapshot → counts toward threshold; harmless: every reconnect sent with a
        // dial in flight already has the refresh pending (LinkState != CACHED: only
        // RECONNECT_REBUILT and pod-roll paths do), so STALE cannot change the next link.
        val failedDialsBefore = environment.session.failedDials
        if (link.isRefreshed) {
            environment.forceReconnect(link.uri)
            recoveryRun.linkState = LinkState.DELIVERED_REFRESHED
            recoveryRun.deliveredRefreshedLink = link.uri
            linkPolicy.markDelivered(link.uri)
            thisLogger().info("Forced the JetBrains Client to reconnect (attempt ${recoveryRun.attempt + 1}).")
        } else {
            environment.reconnectInPlace()
            thisLogger().info(
                "Asked the JetBrains Client to re-dial its current link " +
                    "(attempt ${recoveryRun.attempt + 1})."
            )
        }
        recoveryRun.attempt++
        ReconnectDelivery(timing.nowNanos(), failedDialsBefore)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // dead handle must not crash the watcher: the loop's client-presence check decides
        thisLogger().warn("Could not ask the JetBrains Client to reconnect.", e)
        null
    }

    /** [ReconnectLinkPolicy.linkFor], a failing link build treated as "nothing to send yet". */
    @Suppress("TooGenericExceptionCaught") // the seams throw whatever the platform throws
    private suspend fun reconnectLinkOrNull(recoveryRun: RecoveryRun): ReconnectLink? = try {
        val link = linkPolicy.linkFor(recoveryRun.linkState, recoveryRun.attempt + 1)
        if (link != null) {
            recoveryRun.hasReportedMissingFreshLink = false
        } else if (recoveryRun.linkState.mustRefresh) {
            logMissingFreshLinkOnce(recoveryRun)
        }
        link
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        thisLogger().warn("Could not build a join link for the forced reconnect.", e)
        null
    }

    /**
     * Logs the "stale, no fresh link yet" wait once per stretch.
     *
     * Watcher keeps polling until the link exists → per-call log would flood the one period a live
     * test needs to read.
     */
    private fun logMissingFreshLinkOnce(recoveryRun: RecoveryRun) {
        if (recoveryRun.hasReportedMissingFreshLink) {
            return
        }
        recoveryRun.hasReportedMissingFreshLink = true
        thisLogger().info(
            "The join link is stale and no fresh link is available yet; waiting for the IDE " +
                "server to report one."
        )
    }

    /**
     * Waits one redial window for client dials to prove the link stale, or to show the client is still
     * trying.
     *
     * Window starts at [ReconnectDelivery.sentAtNanos] — when the reconnect was actually delivered — so
     * a slow link build (stale link's status exec) never eats it.
     *
     * Deliberately shorter than [DIAL_CONFIRMATION_MS]: a dial cannot prove an established session this
     * early. Failed-dial run ⇒ link stale; any other dial activity keeps silence escalation flat and
     * lets the next poll apply the confirmation threshold. Declaring recovery here was both unreachable
     * for a fresh dial and could attribute a pre-reconnect handshake to the forced reconnect.
     */
    private suspend fun awaitOutcome(delivery: ReconnectDelivery): DialOutcome {
        val deadlineNanos = delivery.sentAtNanos + millisToNanos(timing.redialWaitMs)
        while (active.get() && timing.nowNanos() < deadlineNanos) {
            timing.sleep(outcomePollDelay(deadlineNanos))
        }
        val failedSinceReconnect = environment.session.failedDials - delivery.failedDialsAtReconnect
        return when {
            failedSinceReconnect >= ReconnectDecisionPolicy.FAILED_DIALS_FOR_STALE -> DialOutcome.STALE
            hasDialedSince(delivery) -> DialOutcome.DIALED
            else -> DialOutcome.NOTHING
        }
    }

    /**
     * Whether the client dialed after [delivery]: failed dial, long-lived dial that ended, or handshake
     * started after the forced reconnect.
     *
     * Any means client answered the forced reconnect → next silence window must not escalate even if
     * session has not confirmed yet.
     */
    private fun hasDialedSince(delivery: ReconnectDelivery): Boolean =
        environment.session.failedDials > delivery.failedDialsAtReconnect
            || environment.session.lastLongLivedDialNanos?.let { it > delivery.sentAtNanos } == true
            || environment.session.oldestActiveHandshakeNanos?.let { it >= delivery.sentAtNanos } == true

    /** Sleeps at most one poll interval, never past the redial deadline, never zero. */
    private fun outcomePollDelay(deadlineNanos: Long): Long {
        val remainingMs = (deadlineNanos - timing.nowNanos()) / NANOS_PER_MILLI
        return minOf(ReconnectDecisionPolicy.OUTCOME_POLL_INTERVAL_MS, maxOf(remainingMs, MIN_SLEEP_MS))
    }

    private suspend fun waitForNextPoll(recoveryRun: RecoveryRun) {
        timing.sleep(pollIntervalAt(recoveryRun.startedAtNanos))
    }

    /** Cheap polls while outage young; long outage polled less often. */
    private fun pollIntervalAt(startedAtNanos: Long): Long =
        if (elapsedMs(startedAtNanos) < timing.longPollAfterMs) {
            timing.pollIntervalMs
        } else {
            timing.longPollIntervalMs
        }

    private fun isBudgetExhausted(startedAtNanos: Long): Boolean =
        elapsedMs(startedAtNanos) >= timing.budgetMs

    private fun elapsedMs(startedAtNanos: Long): Long =
        (timing.nowNanos() - startedAtNanos) / NANOS_PER_MILLI

    private fun millisToNanos(millis: Long): Long = millis * NANOS_PER_MILLI

    /**
     * Link proven stale: next attempt rebuilds it.
     *
     * Called by the pre-reconnect decision (failed dials since it began sampling) and by post-reconnect
     * observation (failed dials since delivery). Log names the evidence, not a "forced reconnect", which
     * does not necessarily precede it.
     */
    private fun staleLink(recoveryRun: RecoveryRun) {
        // failed dials are a dial outcome: client is answering → silence escalation resets
        recoveryRun.consecutiveNothing = 0
        recoveryRun.linkState = recoveryRun.linkState.requestingRefresh()
        thisLogger().info(
            "A run of failed dials proves the join link for port " +
                "${environment.cachedLink.port} stale; refreshing it on the next attempt."
        )
    }

    /**
     * Reports the session a dial outliving [DIAL_CONFIRMATION_MS] established, ending the run.
     *
     * Fast pre-check and decision's [ReconnectDecisionPolicy.Verdict.RECOVERED] observe the same evidence
     * from different points of the iteration → shared sink and reason.
     *
     * @param evidence confirmed handshake identifying the session; two reports with same evidence are
     *   one recovery.
     */
    private fun reportConfirmedSession(recoveryRun: RecoveryRun, evidence: Long?) {
        reportRecovered(recoveryRun, evidence, CONFIRMED_SESSION_REASON)
    }

    /**
     * Reports a recovered session once per confirmed session.
     *
     * `onRemoteEnd` fires per connection → several runs can observe the same session while dial churn
     * settles, each pulling the window to front and logging again.
     *
     * @param evidence timestamp identifying the confirmed session (its handshake), or `null` when it
     *   cannot be named; two reports with same evidence are one recovery.
     */
    private fun reportRecovered(recoveryRun: RecoveryRun, evidence: Long?, reason: String) {
        adoptSessionState(recoveryRun)
        recoveryRun.isFinished = true
        if (evidence != null && evidence == lastReportedEvidence) {
            thisLogger().info("Connection recovery already reported for this session.")
            return
        }
        lastReportedEvidence = evidence
        thisLogger().info("Connection recovery succeeded: $reason.")
        notify(environment.onRecovered, "report the recovered connection")
    }

    /**
     * Carries a confirmed recovery's proof into the next episode.
     *
     * Refreshed link + replacement pod = session's new baseline: next outage must start from them, not
     * from the connect-time link/pod just replaced. [ReconnectLinkPolicy.markDelivered] already recorded
     * the link Guest saw → next forced reconnect with that same link gets a nonce and is not swallowed by
     * Guest's same-link guard.
     */
    private fun adoptSessionState(recoveryRun: RecoveryRun) {
        if (recoveryRun.hasPodRolled) {
            recoveryRun.resolvedPod?.let { sessionPod = it }
        }
        recoveryRun.deliveredRefreshedLink?.let(linkPolicy::adopt)
    }

    private fun reportFailure() {
        thisLogger().warn(
            "Connection recovery failed: the workspace did not come back within " +
                "${timing.budgetMs / MILLIS_PER_SECOND} s."
        )
        notify(environment.onRecoveryFailed, "report the failed recovery")
    }

    private fun stopQuietly(recoveryRun: RecoveryRun, reason: String) {
        recoveryRun.isFinished = true
        thisLogger().info("Stopped connection recovery: $reason.")
    }

    /** Callback must never fail the loop: recovery outcome is already decided by then. */
    private inline fun notify(callback: () -> Unit, action: String) {
        runCatching(callback)
            .onFailure { e -> thisLogger().warn("Could not $action.", e) }
    }

    /**
     * Releases the episode only while [owner] still owns it: a superseded run finishing after a
     * replacement took over leaves the replacement's latch and job untouched.
     *
     * Runs both from the loop's [finally][recoverUntilStopped] and from the job's completion
     * callback, because cancellation can end the job before the loop body ever started.
     */
    private fun releaseOwnership(owner: Any) {
        synchronized(stateLock) {
            if (activeOwner !== owner) {
                return
            }
            activeOwner = null
            active.set(false)
            job = null
        }
    }

    /**
     * Progress of one recovery run: delivered forced reconnects so far, join-link state, cached link
     * stale?, run ended already?
     *
     * Link state is a monotone lattice, [LinkState.CACHED] -> [LinkState.REFRESH_PENDING] ->
     * [LinkState.DELIVERED_REFRESHED]: no transition moves back. Once a rebuilt link was delivered,
     * client dials stay observable for the rest of the run even if a later failed-dial run proves that
     * link stale again → [hasPodRolled] and [LinkState.hasDeliveredRefreshed] never re-disable
     * observation, and the cached link is never sent again.
     */
    private class RecoveryRun(val startedAtNanos: Long) {
        /** Delivered forced reconnects; a failed delivery is retried as the same attempt. */
        var attempt: Int = 0

        /** Cached link stale?, rebuilt one already delivered? */
        var linkState: LinkState = LinkState.CACHED

        /** Workspace pod replaced during this run? */
        var hasPodRolled: Boolean = false

        /** Pod resolved in current iteration: owner of a confirmed fresh link. */
        var resolvedPod: V1Pod? = null

        /** Last refreshed link delivered in this run, adopted once recovery confirms it. */
        var deliveredRefreshedLink: URI? = null

        /** "Stale but no fresh link yet" wait already logged for this run? */
        var hasReportedMissingFreshLink: Boolean = false

        var isFinished: Boolean = false

        /** Consecutive forced reconnects yielding no dial at all; escalates the next silence window. */
        var consecutiveNothing: Int = 0
    }

    /**
     * What the loop knows once a forced reconnect was delivered: observation window start + failed-dial
     * count then. Dial outcomes seen before belong to the previous attempt, not the one measured.
     */
    private class ReconnectDelivery(val sentAtNanos: Long, val failedDialsAtReconnect: Long)

    /** What client's dials said after a forced reconnect. */
    private enum class DialOutcome {
        /** Only failed dials: link the client reconnected with looks stale. */
        STALE,

        /** Client dialed, but the dial has not confirmed a session yet. */
        DIALED,

        /** No dial at all: force a reconnect again (client may have ignored the message). */
        NOTHING
    }
}
