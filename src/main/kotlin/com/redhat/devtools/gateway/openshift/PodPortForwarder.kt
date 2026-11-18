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

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Phase state of one forward session, shared by the session handle ([PortForwardSession]) and the
 * per-connection retry loops.
 *
 * The phase is carried by the session, not by the policy: the same [ForwardRetryPolicy] must fail
 * fast while the wizard waits for the Guest (connect-time) and keep retrying once the Guest is up.
 * [lastSuccessfulHandshakeNanos] is recorded for the recovery watcher, which must never prod a
 * Guest that is establishing a connection.
 */
internal class SessionPhase {
    private val established = AtomicBoolean(false)

    @Volatile
    private var lastHandshakeNanos: Long? = null

    /** Whether the Guest came up for this session; false until [markEstablished]. */
    val isEstablished: Boolean
        get() = established.get()

    /** [System.nanoTime] of the last successful handshake, or null when none succeeded yet. */
    val lastSuccessfulHandshakeNanos: Long?
        get() = lastHandshakeNanos

    /** Idempotent: the phase only ever moves from connect-time to steady-state. */
    fun markEstablished() {
        established.set(true)
    }

    fun recordSuccessfulHandshake() {
        lastHandshakeNanos = System.nanoTime()
    }
}

/**
 * A running port forward session.
 *
 * [localPort] is the loopback port the session listens on, so the forward describes itself and
 * callers do not have to carry the port alongside the session. [close] stops the listener and
 * closes the established connections, so the client reads EOF at once. The listener must not
 * outlive the forward: the pod is pinned when the forward is created, so a re-dial could only
 * reach that same pod.
 */
internal class PortForwardSession(
    val localPort: Int,
    /**
     * Shared with the retry loops; defaults to a fresh one so a caller that only needs the handle
     * (tests, fakes) does not have to carry it.
     */
    private val phase: SessionPhase = SessionPhase(),
    private val stopForward: () -> Unit
) : Closeable {
    /** Whether the Guest is up for this session; see [SessionPhase.isEstablished]. */
    val isEstablished: Boolean
        get() = phase.isEstablished

    /** [System.nanoTime] of the last successful handshake; see [SessionPhase]. */
    val lastSuccessfulHandshakeNanos: Long?
        get() = phase.lastSuccessfulHandshakeNanos

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
     * Answers "can a port forward to [remotePort] on [pod] be established right now?".
     *
     * Opens a real forward and closes it at once: no local listener, no client socket. It proves
     * the whole path (auth, API server, pod, listener), which a pod liveness query cannot. Bounded:
     * a hanging open counts as `false`, because a hanging API server cannot serve the Guest either.
     */
    suspend fun probe(pod: V1Pod, remotePort: Int): Boolean
}

/**
 * Mutable state of one running forward: the pod and ports it bridges, the local sockets currently
 * bridged to it, the session phase shared with the retry loops, and the notification for a
 * connection the pod side ended.
 */
private class ForwardState(
    val pod: V1Pod,
    val localPort: Int,
    val remotePort: Int,
    val onRemoteEnd: () -> Unit
) {
    val ports = PortMapping(localPort, remotePort)
    val clientSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    val phase = SessionPhase()
}

/**
 * Bridges a local loopback port to a port on a DevWorkspace pod via a Kubernetes port-forward,
 * retrying failed handshakes while the pod is alive and the local client stays connected.
 *
 * The listener lifecycle lives here; one attempt is a [ForwardAttempt] and whether a failed
 * attempt may be retried is decided by [ForwardRetryPolicy].
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
    private val retryPolicy = ForwardRetryPolicy(livenessProvider)

    override fun forward(
        pod: V1Pod,
        remotePort: Int,
        onRemoteEnd: () -> Unit
    ): PortForwardSession {
        val serverSocket = ServerSocket(0, LISTEN_BACKLOG, InetAddress.getLoopbackAddress())
        val scope = CoroutineScope(
            // dont cancel if child coroutine fails + use dedicated port-forward dispatcher
            SupervisorJob() + PortForwardDispatcher.dispatcher
        )
        val state = ForwardState(pod, serverSocket.localPort, remotePort, onRemoteEnd)
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
        try {
            forwardWithRetries(clientSocket, state, acceptedAtNanos)
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            logger.warn(
                "Could not port forward to pod ${state.pod.metadata?.name} " +
                    "using port ${state.localPort} -> ${state.remotePort}",
                e)
        } finally {
            state.clientSockets.remove(clientSocket)
            closeQuietly(clientSocket)
        }
    }

    private suspend fun CoroutineScope.forwardWithRetries(
        clientSocket: Socket,
        state: ForwardState,
        acceptedAtNanos: Long
    ) {
        // wraps the client input so the client-gone probe can push back the byte it has to read (see isClientGone)
        val clientIn = PushbackInputStream(clientSocket.getInputStream(), 1)
        var firstFailureNanos: Long? = null
        var attempt = 1
        var keepRetrying = true
        // connect-time is capped by the policy; an established session keeps retrying until its
        // outage budget or the client gives up, so the loop itself has no attempt limit
        while (isActive && keepRetrying) {
            logger.info("Attempt #$attempt: Connecting ${state.localPort} -> ${state.remotePort}...")
            val result = forwardAttempt.attempt(state.pod, state.ports, clientSocket, clientIn) {
                state.phase.recordSuccessfulHandshake()
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
                    firstFailureNanos = firstFailureNanos ?: System.nanoTime()
                    val failure = FailedHandshake(attempt, firstFailureNanos, result.cause)
                    val established = state.phase.isEstablished
                    val retry = retryPolicy.decide(state.pod, state.ports, established, failure)
                    if (retry != RetryDecision.RETRY) {
                        false
                    } else if (clientLeftDuringRetry(clientSocket, clientIn, attempt, established)) {
                        logger.info(
                            "Port forward handshake ${state.localPort} -> ${state.remotePort}: " +
                                "client disconnected; stopping retries"
                        )
                        false
                    } else {
                        true
                    }
                }
            }
            attempt++
        }
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
