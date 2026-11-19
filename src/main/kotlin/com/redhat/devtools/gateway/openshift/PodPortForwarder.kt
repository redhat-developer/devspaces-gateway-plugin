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
import com.redhat.devtools.gateway.util.isCancellationException
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * How long a dial must survive to count as an established RD session.
 *
 * A dial against a stale link is dropped by the Guest's own transport-creation timeout (6 s) —
 * measured 6.5–6.9 s in CRW-13910 live testing — while a real session lives for minutes. 15 s is
 * more than twice the failed-dial lifetime.
 */
internal const val DIAL_CONFIRMATION_MS: Long = 15_000

/**
 * Phase state of one forward session, shared by the session handle ([PortForwardSession]) and the
 * per-connection retry loops.
 *
 * The phase is carried by the session, not by the policy: the same [ForwardRetryPolicy] must fail
 * fast while the wizard waits for the Guest (connect-time) and keep retrying once the Guest is up.
 *
 * The dial outcomes are recorded for the recovery watcher. They are deliberately about *survival*,
 * not about bytes flowing: a stale-link dial is copied for seconds before the Guest drops it, so
 * neither "a connection is being copied" nor "the last dial lasted a while" proves an RD session.
 * Only a dial that outlived [confirmationMs] does.
 */
internal class SessionPhase(
    /**
     * How long a dial must survive to count as an established session. Overridable so tests can
     * exercise both sides of the threshold with short real dials.
     */
    private val confirmationMs: Long = DIAL_CONFIRMATION_MS
) {
    private val established = AtomicBoolean(false)
    private val failedDialCount = AtomicLong(0)

    /**
     * Handshakes of the connections currently being copied, with a count per timestamp.
     *
     * Keyed by [System.nanoTime] of the handshake rather than by connection, because the phase only
     * ever sees that timestamp. A count keeps two connections that handshook in the same nanosecond
     * distinct: removing one must not hide the other, or the watcher could lose the oldest pending
     * handshake and prod a session that is still establishing.
     */
    private val activeHandshakes = ConcurrentHashMap<Long, Int>()

    @Volatile
    private var lastHandshakeNanos: Long? = null

    @Volatile
    private var lastFailedNanos: Long? = null

    @Volatile
    private var lastLongLivedNanos: Long? = null

    /** Whether the Guest came up for this session; false until [markEstablished]. */
    val isEstablished: Boolean
        get() = established.get()

    /**
     * Whether at least one connection whose handshake succeeded is still being copied.
     *
     * Not recovery evidence: see the class KDoc. The watcher corroborates with
     * [oldestActiveHandshakeNanos].
     */
    val hasActiveForward: Boolean
        get() = activeHandshakes.isNotEmpty()

    /** [System.nanoTime] of the last successful handshake, or null when none succeeded yet. */
    val lastSuccessfulHandshakeNanos: Long?
        get() = lastHandshakeNanos

    /**
     * The earliest handshake among the connections still being copied, or null when none is.
     *
     * This is how a connection that has not ended yet can still confirm: a handshake this old has
     * survived longer than [confirmationMs], so it is an established session rather than a dial the
     * Guest is about to drop.
     */
    val oldestActiveHandshakeNanos: Long?
        get() = activeHandshakes.keys.minOrNull()

    /** [System.nanoTime] when the last failed dial ended; see [DIAL_CONFIRMATION_MS]. */
    val lastFailedDialNanos: Long?
        get() = lastFailedNanos

    /** [System.nanoTime] when the last long-lived dial ended; see [DIAL_CONFIRMATION_MS]. */
    val lastLongLivedDialNanos: Long?
        get() = lastLongLivedNanos

    /**
     * Monotone count of failed dials since the forward started. The watcher compares it with the
     * count it saw at its last prod: the timestamp alone cannot tell several dials apart when more
     * than one ends between two samples.
     */
    val failedDials: Long
        get() = failedDialCount.get()

    /** Idempotent: the phase only ever moves from connect-time to steady-state. */
    fun markEstablished() {
        established.set(true)
    }

    /** Records *when* the handshake succeeded: the caller keeps the same value per connection. */
    fun recordSuccessfulHandshake(atNanos: Long) {
        lastHandshakeNanos = atNanos
    }

    /** A connection's handshake succeeded, so it is being copied from [handshakeNanos] on. */
    fun recordActiveForward(handshakeNanos: Long) {
        activeHandshakes.merge(handshakeNanos, 1, Int::plus)
    }

    /**
     * A connection ended. [handshakeNanos] is the handshake of *this* connection: `null` when it
     * never handshook, in which case it never counted as an active forward and is not classified.
     *
     * The classification is about survival: a dial that ended within [confirmationMs] of its
     * handshake failed (the Guest dropped the stale-link dial), a later one was a real session the
     * server ended.
     */
    fun recordEndedForward(handshakeNanos: Long?) {
        if (handshakeNanos == null) {
            return
        }
        activeHandshakes.computeIfPresent(handshakeNanos) { _, count ->
            (count - 1).takeIf { it > 0 }
        }
        val endNanos = System.nanoTime()
        if (endNanos - handshakeNanos < confirmationMs * NANOS_PER_MILLI) {
            failedDialCount.incrementAndGet()
            lastFailedNanos = endNanos
        } else {
            lastLongLivedNanos = endNanos
        }
    }
}

/**
 * The Guest's dial outcomes on one forward session, as the recovery watcher observes them.
 *
 * Narrower than [PortForwardSession], and deliberately so: it exposes only facts that can confirm a
 * session or prove a link stale. "A connection is being copied" is not one of them - a stale-link
 * dial is copied for seconds before the Guest drops it - so the watcher cannot mistake it for
 * recovery.
 */
internal interface ForwardDials {
    /**
     * The earliest handshake among the connections still being copied, or null when none is. A
     * handshake older than [DIAL_CONFIRMATION_MS] confirms an established RD session.
     */
    val oldestActiveHandshakeNanos: Long?

    /** [System.nanoTime] when the last long-lived dial ended, or null when none lasted long. */
    val lastLongLivedDialNanos: Long?

    /** Monotone count of failed dials; the watcher compares it across a prod. */
    val failedDials: Long
}

/**
 * A running port forward session.
 *
 * [localPort] is the loopback port the session listens on, so the forward describes itself and
 * callers do not have to carry the port alongside the session. [close] stops the listener and
 * closes the established connections, so the client reads EOF at once. The listener must not
 * outlive the session it bridges: closing it is what keeps a re-dial away from a stopped
 * workspace. The forward itself may retarget to the pod that replaced the one it was opened
 * against, so a re-dial reaches whichever pod currently serves the workspace.
 */
internal class PortForwardSession(
    val localPort: Int,
    /**
     * Shared with the retry loops; defaults to a fresh one so a caller that only needs the handle
     * (tests, fakes) does not have to carry it.
     */
    private val phase: SessionPhase = SessionPhase(),
    private val stopForward: () -> Unit
) : ForwardDials, Closeable {
    /** Whether the Guest is up for this session; see [SessionPhase.isEstablished]. */
    val isEstablished: Boolean
        get() = phase.isEstablished

    /** [System.nanoTime] of the last successful handshake; see [SessionPhase]. */
    val lastSuccessfulHandshakeNanos: Long?
        get() = phase.lastSuccessfulHandshakeNanos

    /** Whether a connection whose handshake succeeded is still being copied; see [SessionPhase]. */
    val hasActiveForward: Boolean
        get() = phase.hasActiveForward

    /** [System.nanoTime] when the last failed dial ended; see [SessionPhase]. */
    val lastFailedDialNanos: Long?
        get() = phase.lastFailedDialNanos

    override val oldestActiveHandshakeNanos: Long?
        get() = phase.oldestActiveHandshakeNanos

    override val lastLongLivedDialNanos: Long?
        get() = phase.lastLongLivedDialNanos

    override val failedDials: Long
        get() = phase.failedDials

    /** Switches retries to the steady-state policy; call once the Guest is up. Idempotent. */
    fun markEstablished() = phase.markEstablished()

    override fun close() = stopForward()
}

/**
 * Opens port forwards to a pod.
 *
 * The seam exists so connection lifecycle and recovery code can be exercised without a cluster:
 * production wires [PodPortForwarder], tests supply a fake.
 */
internal interface PortForwarder {
    /**
     * Starts a forward to [remotePort] on a free loopback port the forwarder picks and binds
     * itself, so port allocation cannot race between "find a free port" and "listen on it".
     * The returned session is already listening.
     *
     * @param onRemoteEnd invoked when an established connection ends other than by a clean
     * client-side close: the pod side ended it (EOF or failure), or the copy failed while talking
     * to the pod. A client that closed itself does not invoke it: the client is already gone.
     */
    @Throws(IOException::class)
    fun forward(pod: V1Pod, remotePort: Int, onRemoteEnd: () -> Unit): PortForwardSession

    /**
     * Starts a forward that follows the pod which currently serves the workspace.
     *
     * When a handshake fails because [pod] is gone, [resolvePod] may name the pod that replaced it,
     * and the forward retargets to that pod instead of ending. A resolver that returns `null` or
     * the pod the failed attempt already used keeps the "pod gone" outcome terminal, so a
     * forwarder that cannot resolve anything behaves exactly like [forward] with a pinned pod.
     *
     * @param resolvePod resolves the workspace's current running and ready pod; it may block on
     * the API server and is therefore called only from the retry loop, never on the caller's
     * thread.
     * @param onRemoteEnd see [forward].
     */
    @Throws(IOException::class)
    fun forward(
        pod: V1Pod,
        remotePort: Int,
        resolvePod: () -> V1Pod?,
        onRemoteEnd: () -> Unit
    ): PortForwardSession = forward(pod, remotePort, onRemoteEnd)

    /**
     * Answers "can a port forward to [remotePort] on [pod] be established right now?".
     *
     * Opens a real forward and closes it at once: no local listener, no client socket. It proves
     * the whole path (auth, API server, pod, listener), which a pod liveness query cannot. Bounded:
     * a hanging open counts as `false`, because a hanging API server cannot serve the Guest either.
     */
    suspend fun probe(pod: V1Pod, remotePort: Int): Boolean
}

/**
 * Mutable state of one running forward: the pod it bridges, the local sockets currently bridged to
 * it, the session phase shared with the retry loops, and the notification for a connection the pod
 * side ended.
 *
 * The pod is retargetable: when the workspace's pod is replaced while the DevWorkspace stays
 * Running (eviction, drain, OOM kill), the retry loop swaps in the replacement instead of ending a
 * session that could recover. Concurrent connections of one session may each see the old pod gone
 * and resolve a replacement, so the swap is idempotent.
 */
private class ForwardState(
    pod: V1Pod,
    /** Resolves the pod that currently serves the workspace; see [PortForwarder.forward]. */
    val resolvePod: () -> V1Pod?,
    val localPort: Int,
    val remotePort: Int,
    /** Survival a dial needs to count as an established session; see [SessionPhase]. */
    confirmationMs: Long,
    val onRemoteEnd: () -> Unit
) {
    /**
     * Guards [currentPod] for the swap. A plain lock, not a `Mutex`: the critical section is an
     * in-memory reference swap and never suspends, so it cannot hold up a dispatcher thread, and
     * the suspend retry loop reads the pod through [pod] outside the lock.
     */
    private val podLock = Any()

    private var currentPod: V1Pod = pod

    val ports = PortMapping(localPort, remotePort)
    val clientSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    val phase = SessionPhase(confirmationMs)

    /** The pod this forward currently targets. */
    val pod: V1Pod
        get() = synchronized(podLock) { currentPod }

    /**
     * Retargets the forward to [replacement].
     *
     * Idempotent: a connection that resolved the pod another connection already swapped in gets
     * `null` instead of a second swap, so concurrent re-dials never thrash between replacements.
     *
     * @return the pod the forward targeted before the swap, or `null` when it already targeted
     * [replacement].
     */
    fun retarget(replacement: V1Pod): V1Pod? = synchronized(podLock) {
        if (isSamePod(currentPod, replacement)) {
            return@synchronized null
        }
        val previous = currentPod
        currentPod = replacement
        previous
    }
}

/**
 * Whether two pod references name the same pod.
 *
 * The UID is a pod's identity: a replaced pod keeps the name during a roll, so a name comparison
 * alone would hide the replacement and keep the forward pinned to a dead pod. The namespace/name
 * pair is the fallback for a pod without a UID (a synthetic pod in a test).
 *
 * Shared with the recovery watcher: the forwarder retargets on a pod roll and the watcher declares
 * the cached link stale on one, so both must agree on what "the same pod" means. One rule only.
 */
internal fun isSamePod(first: V1Pod, second: V1Pod): Boolean {
    val firstUid = first.metadata?.uid
    val secondUid = second.metadata?.uid
    if (firstUid != null && secondUid != null) {
        return firstUid == secondUid
    }
    return first.metadata?.namespace == second.metadata?.namespace &&
        first.metadata?.name == second.metadata?.name
}

/** Identifies a pod in log lines: the name alone cannot tell two generations of a pod apart. */
internal fun describePod(pod: V1Pod): String =
    "name=${pod.metadata?.name}, uid=${pod.metadata?.uid}"

/**
 * Dial state of one accepted connection.
 *
 * At most one handshake can succeed for a connection: a successful handshake moves the attempt into
 * copying, which closes the connection when it returns, so the retry loop starts no further attempt.
 */
private class DialState {
    var handshakeNanos: Long? = null
}

/**
 * Bridges a local loopback port to a port on a DevWorkspace pod via a Kubernetes port-forward,
 * retrying failed handshakes while the pod is alive and the local client stays connected.
 *
 * The listener lifecycle lives here; one attempt is a [ForwardAttempt] and whether a failed
 * attempt may be retried is decided by [ForwardRetryPolicy]. A pod that is replaced while the
 * DevWorkspace stays Running does not end the session: the forward retargets to the pod that
 * replaced it, so a re-dial reaches whatever pod currently serves the workspace.
 */
@Suppress("TooManyFunctions") // the forwarder seam plus its socket plumbing form one cohesive unit
internal class PodPortForwarder(
    client: ApiClient,
    /**
     * Upper bound of [probe]; a hanging open counts as a failed probe.
     * Overridable so tests can exercise the bound with a short delay.
     */
    private val probeTimeoutMs: Long = PROBE_TIMEOUT_MS,
    /**
     * Resolves the pod that currently serves the workspace, used when the pod a forward was opened
     * against is gone. Defaults to no resolution, so a forwarder that was not told how to find the
     * workspace's current pod keeps its pinned pod and "pod gone" stays terminal.
     */
    private val resolvePod: () -> V1Pod? = { null },
    /**
     * Reads the clock the retry decisions use: the outage budget is measured against it.
     * Overridable so a test can put the first failure of a series into the past.
     */
    private val nowNanos: () -> Long = System::nanoTime,
    /**
     * Survival a dial needs to count as an established RD session; see [DIAL_CONFIRMATION_MS].
     * Overridable so tests can exercise both sides of the threshold with short real dials.
     */
    private val confirmationMs: Long = DIAL_CONFIRMATION_MS,
    /**
     * Returns pod liveness state to decide whether a failed forward may be retried.
     * Overridable in tests because [PodLiveness] is module-internal.
     */
    livenessProvider: suspend (V1Pod) -> PodLiveness = PodLivenessProbe(client)::query
) : PortForwarder {

    companion object {
        private const val CLIENT_GONE_PROBE_TIMEOUT_MS = 100
        private const val LISTEN_BACKLOG = 50

        /** OkHttp's default 10s is too slow to poll with; see the plan's probe timeout constant. */
        const val PROBE_TIMEOUT_MS: Long = 3_000
    }

    private val logger = logger<PodPortForwarder>()
    private val forwardAttempt = ForwardAttempt(client)
    private val retryPolicy = ForwardRetryPolicy(nowNanos, livenessProvider)

    override fun forward(
        pod: V1Pod,
        remotePort: Int,
        onRemoteEnd: () -> Unit
    ): PortForwardSession = openSession(pod, remotePort, resolvePod, onRemoteEnd)

    override fun forward(
        pod: V1Pod,
        remotePort: Int,
        resolvePod: () -> V1Pod?,
        onRemoteEnd: () -> Unit
    ): PortForwardSession = openSession(pod, remotePort, resolvePod, onRemoteEnd)

    private fun openSession(
        pod: V1Pod,
        remotePort: Int,
        resolvePod: () -> V1Pod?,
        onRemoteEnd: () -> Unit
    ): PortForwardSession {
        val serverSocket = ServerSocket(0, LISTEN_BACKLOG, InetAddress.getLoopbackAddress())
        val scope = CoroutineScope(
            // dont cancel if child coroutine fails + use dedicated port-forward dispatcher
            SupervisorJob() + PortForwardDispatcher.dispatcher
        )
        val state = ForwardState(
            pod,
            resolvePod,
            serverSocket.localPort,
            remotePort,
            confirmationMs,
            onRemoteEnd
        )
        scope.acceptConnections(serverSocket, state)
        return PortForwardSession(state.localPort, state.phase) {
            closeQuietly(serverSocket)
            scope.cancel()
            closeClientSockets(state)
        }
    }

    /**
     * Opens a real forward to [remotePort] on [pod] and closes it at once, bounded to
     * [probeTimeoutMs]. No listener and no client socket: the caller only learns whether the path
     * is usable right now.
     *
     * The open runs in a scope that is not a child of the caller (the [PodLivenessProbe.query]
     * pattern), so the bound can return `false` without joining the blocking
     * `PortForward.forward` call, which may keep running in the background. A timeout counts as
     * `false`: a hanging API server cannot serve the Guest either.
     */
    override suspend fun probe(pod: V1Pod, remotePort: Int): Boolean {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val open = scope.async { forwardAttempt.tryOpen(pod, remotePort) }
        return try {
            withTimeoutOrNull(probeTimeoutMs) { open.await() } ?: false
        } finally {
            scope.cancel()
        }
    }

    /**
     * Closes every socket accepted so far so the connected clients read EOF.
     *
     * [CoroutineScope.cancel] alone does not unblock the copy pumps: `InputStream.read` ignores
     * coroutine cancellation. Closing the sockets is what makes the client notice the loss.
     */
    private fun closeClientSockets(state: ForwardState) {
        val sockets = state.clientSockets.toList()
        state.clientSockets.clear()
        if (sockets.isNotEmpty()) {
            logger.info(
                "Closing ${sockets.size} client connection(s) of port forward " +
                    "on local port ${state.localPort}"
            )
        }
        sockets.forEach { closeQuietly(it) }
    }

    private fun CoroutineScope.acceptConnections(serverSocket: ServerSocket, state: ForwardState) {
        launch {
            logger.info(
                "Starting port forward on local port ${state.localPort} " +
                    "(transport=java-websocket, copyBuffer=${ForwardAttempt.COPY_BUFFER_SIZE}, " +
                    "dispatcher=devspaces-port-forward)"
            )

            while (isActive) {
                val clientSocket = createClientSocket(serverSocket) ?: break
                clientSocket.tcpNoDelay = true
                state.clientSockets.add(clientSocket)

                launch {
                    handleConnection(clientSocket, state)
                }
            }
        }
    }

    private suspend fun createClientSocket(serverSocket: ServerSocket): Socket? {
        return try {
            withContext(NonCancellable) {
                // block until connection is accepted
                serverSocket.accept()
            }
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            logger.info("Server socket stopped accepting connections.")
            null
        }
    }

    private suspend fun CoroutineScope.handleConnection(clientSocket: Socket, state: ForwardState) {
        val acceptedAtNanos = System.nanoTime()
        val dial = DialState()
        try {
            forwardWithRetries(clientSocket, state, acceptedAtNanos, dial)
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            logger.warn(
                "Could not port forward to pod ${state.pod.metadata?.name} " +
                    "using port ${state.localPort} -> ${state.remotePort}",
                e)
        } finally {
            // this connection is over, whether it was copied, failed or was cancelled: a connection
            // that never handshook passes null and must not decrement the active-forward counter
            state.phase.recordEndedForward(dial.handshakeNanos)
            state.clientSockets.remove(clientSocket)
            closeQuietly(clientSocket)
        }
    }

    private suspend fun CoroutineScope.forwardWithRetries(
        clientSocket: Socket,
        state: ForwardState,
        acceptedAtNanos: Long,
        dial: DialState
    ) {
        // wraps the client input so the client-gone probe can push back the byte it has to read (see isClientGone)
        val clientIn = PushbackInputStream(clientSocket.getInputStream(), 1)
        var firstFailureNanos: Long? = null
        var attempt = 1
        var keepRetrying = true
        // connect-time is capped by the policy; an established session keeps retrying until its
        // outage budget or the client gives up, so the loop itself has no attempt limit
        while (isActive && keepRetrying) {
            // one read per iteration: a concurrent connection may retarget the forward between the
            // attempt and the decision, and the decision must judge the pod this attempt used
            val pod = state.pod
            logger.info("Attempt #$attempt: Connecting ${state.localPort} -> ${state.remotePort}...")
            val result = forwardAttempt.attempt(pod, state.ports, clientSocket, clientIn) {
                onHandshakeSucceeded(state.phase, dial)
            }
            keepRetrying = when (result) {
                // the copy already closed the local socket: any outcome is terminal
                is AttemptResult.Ended -> {
                    logCopyEnd(result.copy, state.localPort, state.remotePort, acceptedAtNanos)
                    // a client-side failure is not a client-initiated close: on a messy pod death
                    // the client pump often fails first, and the Guest still has to report the loss
                    if (!result.copy.isClosedByClient) {
                        state.onRemoteEnd()
                    }
                    false
                }

                is AttemptResult.HandshakeFailed -> {
                    firstFailureNanos = firstFailureNanos ?: nowNanos()
                    val failure = FailedHandshake(attempt, firstFailureNanos, result.cause)
                    val established = state.phase.isEstablished
                    val retry = retryPolicy.decide(pod, state.ports, established, failure)
                    when {
                        retry == RetryDecision.POD_GONE && retarget(state, pod) -> {
                            // the replacement restarts the attempt (delay) series: the dead pod's
                            // failure must not shorten it. In steady state it does NOT restart the
                            // outage budget, which is the safety cap against a stuck socket and
                            // keeps running from this connection's first failure across any number
                            // of retargets - otherwise repeated pod churn would defeat the cap.
                            // Connect-time resets it too: there the window is attempt-capped and
                            // the dead pod's failure time must not shorten the replacement's 2s.
                            attempt = 0
                            firstFailureNanos = seriesStartAfterRetarget(firstFailureNanos, established)
                            true
                        }

                        retry != RetryDecision.RETRY -> false

                        clientLeftDuringRetry(clientSocket, clientIn, attempt, established) -> {
                            logger.info(
                                "Port forward handshake ${state.localPort} -> ${state.remotePort}: " +
                                    "client disconnected; stopping retries"
                            )
                            false
                        }

                        else -> true
                    }
                }
            }
            attempt++
        }
    }

    /**
     * The start of the retry series after a retarget.
     *
     * Connect-time restarts the series completely: its 2 s window is attempt-capped anyway, and the
     * dead pod's failure time must not shorten the replacement's window. An established session
     * keeps its original first failure, so the outage budget - the safety cap against a stuck
     * socket - keeps running across any number of pod replacements instead of restarting on each.
     */
    private fun seriesStartAfterRetarget(firstFailureNanos: Long?, established: Boolean): Long? =
        if (established) firstFailureNanos else null

    /**
     * The policy says the pod this attempt used is gone: follow the pod that currently serves the
     * workspace instead of ending the forward.
     *
     * A replacement only counts when it is a *different* pod than [attemptedPod]. The resolver
     * answers "which pod serves the workspace now", so while the failed pod is still the only one
     * running it names exactly that pod, and retargeting to it would only loop.
     *
     * [ForwardState.resolvePod] lists pods on the API server, so it runs outside the state lock.
     * Concurrent connections of one session may each resolve the replacement; the swap is
     * idempotent, so a candidate another connection already swapped in is recognized as the
     * current pod and no connection swaps twice for the same roll. A failed resolution counts as
     * "no replacement known" and ends the forward, like a `null` answer.
     *
     * @return whether the loop should retry, now against the replacement.
     */
    private fun retarget(state: ForwardState, attemptedPod: V1Pod): Boolean {
        val replacement = resolveReplacement(state)
        if (replacement == null || isSamePod(attemptedPod, replacement)) {
            logger.info(
                "Port forward ${state.localPort} -> ${state.remotePort}: pod " +
                    "${describePod(attemptedPod)} is gone and no replacement serves the " +
                    "workspace; stopping"
            )
            return false
        }
        logRetarget(state, state.retarget(replacement), replacement)
        return true
    }

    /**
     * Logs what the swap did: a `null` [previous] means another connection of the session already
     * swapped this pod in, so this connection only joins it instead of swapping again.
     */
    private fun logRetarget(state: ForwardState, previous: V1Pod?, replacement: V1Pod) {
        val forward = "${state.localPort} -> ${state.remotePort}"
        if (previous == null) {
            logger.info(
                "Port forward $forward: a concurrent connection already retargeted to pod " +
                    "${describePod(replacement)}; retrying"
            )
        } else {
            logger.info(
                "Port forward $forward: retargeting from pod ${describePod(previous)} to pod " +
                    "${describePod(replacement)}; retrying immediately"
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // a failed pod list only means "no replacement known"
    private fun resolveReplacement(state: ForwardState): V1Pod? = try {
        state.resolvePod()
    } catch (e: Exception) {
        if (e.isCancellationException()) throw e
        logger.info(
            "Port forward ${state.localPort} -> ${state.remotePort}: could not resolve a " +
                "replacement pod (${e.javaClass.simpleName}: ${e.message}); not retrying"
        )
        null
    }

    /**
     * Records a successful handshake with its own timestamp: the session keeps the latest one for
     * the recovery watcher, while [dial] keeps *this* connection's, so the failed/long-lived
     * classification when the connection ends uses this connection's lifetime only.
     */
    private fun onHandshakeSucceeded(phase: SessionPhase, dial: DialState) {
        if (dial.handshakeNanos != null) {
            return // already counted: one handshake per accepted connection
        }
        val handshakeNanos = System.nanoTime()
        dial.handshakeNanos = handshakeNanos
        phase.recordSuccessfulHandshake(handshakeNanos)
        phase.recordActiveForward(handshakeNanos)
    }

    /**
     * Waits the policy's reconnect delay for [attempt] in the current session phase, checking the
     * client before and after it: a client that already left will not come back, so waiting the
     * whole delay for it is pointless.
     */
    private suspend fun clientLeftDuringRetry(
        clientSocket: Socket,
        clientIn: PushbackInputStream,
        attempt: Int,
        established: Boolean
    ): Boolean {
        if (isClientGone(clientSocket, clientIn)) return true
        @Suppress("ConvertLongToDuration")
        delay(retryPolicy.reconnectDelayMs(attempt, established))
        return isClientGone(clientSocket, clientIn)
    }

    /**
     * Detects a client that disconnected while its handshake was being retried.
     *
     * A peer close is not reflected by [Socket.isClosed] (only a local [Socket.close] is) and
     * `InputStream.available` cannot tell a silent peer from a closed one: only a read reports the
     * peer's end-of-stream. The probe therefore reads a single byte with a short
     * [Socket.setSoTimeout]: end-of-stream or an I/O error means the client is gone, a byte or a
     * read timeout means it is still connected. A read byte is pushed back onto [clientIn] so a
     * client that already sent payload does not lose it when a later handshake attempt succeeds
     * and starts copying.
     */
    private fun isClientGone(clientSocket: Socket, clientIn: PushbackInputStream): Boolean {
        return try {
            clientSocket.soTimeout = CLIENT_GONE_PROBE_TIMEOUT_MS
            when (val byte = clientIn.read()) {
                -1 -> true
                else -> {
                    clientIn.unread(byte)
                    false
                }
            }
        } catch (_: SocketTimeoutException) {
            false // nothing arrived within the window: the client is silent, not gone
        } catch (_: IOException) {
            true // connection reset or the socket was closed locally
        } finally {
            runCatching { clientSocket.soTimeout = 0 }
        }
    }

    private fun logCopyEnd(
        result: CopyResult,
        localPort: Int,
        remotePort: Int,
        acceptedAtNanos: Long
    ) {
        val elapsedMs = (System.nanoTime() - acceptedAtNanos) / NANOS_PER_MILLI
        val message =
            "Port forward $localPort -> $remotePort ended (reason=${describeReason(result)}, " +
                "elapsed=${elapsedMs}ms); closing local socket"
        when (result) {
            is CopyResult.PeerClosed ->
                logger.info(message)
            is CopyResult.Failed ->
                logger.info(message, result.cause)
        }
    }

    private fun describeReason(result: CopyResult): String {
        val sideName = result.side.name.lowercase()
        return when (result) {
            is CopyResult.PeerClosed -> "peer closed ($sideName side)"
            is CopyResult.Failed ->
                "failed ($sideName side): ${result.cause.javaClass.simpleName}: ${result.cause.message}"
        }
    }
}
