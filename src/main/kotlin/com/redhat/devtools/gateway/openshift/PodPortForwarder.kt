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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

private const val NANOS_PER_MILLI = 1_000_000L

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
    private val stopForward: () -> Unit
) : Closeable {
    override fun close() = stopForward()
}

/**
 * Opens a loopback port forward to a port on a pod.
 *
 * The seam exists so connection lifecycle code can be exercised without a cluster:
 * production wires [PodPortForwarder], tests supply a fake session.
 */
internal fun interface PortForwarder {
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
}

/**
 * Mutable state of one running forward: the pod and ports it bridges, the local sockets currently
 * bridged to it, and the notification for a connection the pod side ended.
 */
private class ForwardState(
    val pod: V1Pod,
    val localPort: Int,
    val remotePort: Int,
    val onRemoteEnd: () -> Unit
) {
    val ports = PortMapping(localPort, remotePort)
    val clientSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
}

/**
 * Bridges a local loopback port to a port on a DevWorkspace pod via a Kubernetes port-forward,
 * retrying failed handshakes while the pod is alive and the local client stays connected.
 *
 * The listener lifecycle lives here; one attempt is a [ForwardAttempt] and whether a failed
 * attempt may be retried is decided by [ForwardRetryPolicy].
 */
internal class PodPortForwarder(
    client: ApiClient,
    /**
     * Returns pod liveness state to decide whether a failed forward may be retried.
     * Overridable in tests because [PodLiveness] is module-internal.
     */
    livenessProvider: suspend (V1Pod) -> PodLiveness = PodLivenessProbe(client)::query
) : PortForwarder {

    companion object {
        private const val CLIENT_GONE_PROBE_TIMEOUT_MS = 100
        private const val LISTEN_BACKLOG = 50
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
        return PortForwardSession(state.localPort) {
            closeQuietly(serverSocket)
            scope.cancel()
            closeClientSockets(state)
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
        for (attempt in 1..ForwardRetryPolicy.CONNECT_ATTEMPTS) {
            if (!isActive) return
            logger.info("Attempt #$attempt: Connecting ${state.localPort} -> ${state.remotePort}...")
            val isTerminal =
                when (val result = forwardAttempt.attempt(state.pod, state.ports, clientSocket, clientIn)) {
                    // the copy already closed the local socket: any outcome is terminal
                    is AttemptResult.Ended -> {
                        logCopyEnd(result.copy, state.localPort, state.remotePort, acceptedAtNanos)
                        // a client-side failure is not a client-initiated close: on a messy pod death
                        // the client pump often fails first, and the Guest still has to report the loss
                        if (!result.copy.isClosedByClient) {
                            state.onRemoteEnd()
                        }
                        true
                    }

                    is AttemptResult.HandshakeFailed -> {
                        val failureStartedAt = firstFailureNanos ?: System.nanoTime()
                        firstFailureNanos = failureStartedAt
                        retryPolicy.decide(
                            state.pod,
                            failureStartedAt,
                            attempt,
                            state.ports,
                            result.cause
                        ) != RetryDecision.RETRY
                    }
                }
            if (isTerminal) break
            if (clientLeftDuringRetry(clientSocket, clientIn)) {
                logger.info(
                    "Port forward handshake ${state.localPort} -> ${state.remotePort}: " +
                        "client disconnected; stopping retries"
                )
                return
            }
        }
    }

    /**
     * Waits the reconnect delay, checking the client before and after it: a client that already left
     * will not come back, so waiting the whole delay for it is pointless.
     */
    private suspend fun clientLeftDuringRetry(
        clientSocket: Socket,
        clientIn: PushbackInputStream
    ): Boolean {
        if (isClientGone(clientSocket, clientIn)) return true
        @Suppress("ConvertLongToDuration")
        delay(ForwardRetryPolicy.RECONNECT_DELAY_MS)
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
