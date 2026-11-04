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
import io.kubernetes.client.PortForward
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * A running port forward.
 *
 * [close] stops the listener and closes the established connections, so the client reads EOF at
 * once.
 */
internal interface PortForwardSession : Closeable

/** Which pump of a forwarded connection ended it: the pod (remote) or the local client. */
private enum class CopySide { REMOTE, CLIENT }

/** Why a bidirectional stream copy ended, and which side ended it. */
private sealed interface CopyResult {
    val side: CopySide

    /** The pump reached EOF: that side closed the connection normally. */
    data class PeerClosed(override val side: CopySide) : CopyResult

    /** The pump failed while copying. */
    data class Failed(override val side: CopySide, val cause: Throwable) : CopyResult
}

/** Whether the client ended the connection cleanly itself: the Guest closed and is already gone. */
private val CopyResult.isClosedByClient: Boolean
    get() = this is CopyResult.PeerClosed && side == CopySide.CLIENT

private fun CopyResult.reason(): String = when (this) {
    is CopyResult.PeerClosed -> "peer closed (${side.name.lowercase()} side)"
    is CopyResult.Failed -> "failed (${side.name.lowercase()} side): ${cause.javaClass.simpleName}: ${cause.message}"
}

/**
 * Outcome of a single forwarding attempt, split by phase.
 *
 * Only [HandshakeFailed] is retryable: once the streams are copying, the local socket is closed when
 * the copy ends, so a retry on the same socket cannot succeed.
 */
private sealed interface AttemptResult {
    /** [PortForward.forward] threw: no streams were established. */
    data class HandshakeFailed(val cause: Throwable) : AttemptResult

    /** The streams were established and the copy already finished. */
    data class Ended(val copy: CopyResult) : AttemptResult
}

/** Why a failed handshake is or is not retried. */
private enum class RetryDecision {
    RETRY,
    POD_GONE,
    BUDGET_EXHAUSTED,
    ATTEMPTS_EXHAUSTED
}

/** The local/remote port pair a forwarding attempt bridges. */
private data class PortMapping(val localPort: Int, val remotePort: Int)

/**
 * Mutable state of one running forward: the pod resolver and ports it bridges, the local sockets
 * currently bridged to it, and the notification for a connection the pod side ended.
 */
private class ForwardState(
    val resolvePod: suspend () -> V1Pod,
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
 */
internal class PodPortForwarder(
    private val client: ApiClient,
    /**
     * Returns pod liveness state to decide whether a failed forward may be retried.
     * Overridable in tests because [PodLiveness] is module-internal.
     */
    private val livenessProvider: suspend (V1Pod) -> PodLiveness = PodLivenessProbe(client)::query
) {

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
        // budget math:
        // attempts land at ~0s, ~1.2s and ~2.4s (1s reconnect delay + 2x100ms client-gone probes between attempts),
        // so the 2s retry budget stops the loop after the 3rd attempt — a higher cap could never be reached.
        private const val CONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY: Long = 1000
        private const val RETRY_BUDGET_MS = 2_000L
        private const val CLIENT_GONE_PROBE_TIMEOUT_MS = 100
    }

    private val logger = logger<PodPortForwarder>()

    /**
     * Accepts local connections on [localPort] and forwards each to [remotePort] on the pod
     * returned by [resolvePod].
     *
     * [resolvePod] is invoked once per accepted connection, so a pod that was recreated (new name
     * or UID) is picked up without restarting the forwarder.
     *
     * @param onRemoteEnd invoked when an established connection ends other than by a clean
     * client-side close: the pod side ended it (EOF or failure), or the copy failed while talking
     * to the pod. A client that closed itself does not invoke it: the client is already gone.
     */
    @Throws(IOException::class)
    fun forward(
        resolvePod: suspend () -> V1Pod,
        localPort: Int,
        remotePort: Int,
        onRemoteEnd: () -> Unit = {}
    ): PortForwardSession {
        val serverSocket = ServerSocket(localPort, 50, InetAddress.getLoopbackAddress())
        val scope = CoroutineScope(
            // dont cancel if child coroutine fails + use dedicated port-forward dispatcher
            SupervisorJob() + PortForwardDispatcher.dispatcher
        )
        val state = ForwardState(resolvePod, localPort, remotePort, onRemoteEnd)
        scope.acceptConnections(serverSocket, state)
        return object : PortForwardSession {
            override fun close() {
                closeQuietly(serverSocket)
                scope.cancel()
                closeClientSockets(state)
            }
        }
    }

    /** Forwards to [pod] pinned at forward creation; see [forward] for the remaining parameters. */
    @Throws(IOException::class)
    fun forward(
        pod: V1Pod,
        localPort: Int,
        remotePort: Int,
        onRemoteEnd: () -> Unit = {}
    ): PortForwardSession =
        forward(resolvePod = { pod }, localPort, remotePort, onRemoteEnd)

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
                    "(transport=java-websocket, copyBuffer=$COPY_BUFFER_SIZE, dispatcher=devspaces-port-forward)"
            )

            while (isActive) {
                val clientSocket = createClientSocket(serverSocket) ?: break
                applyClientSocketOptions(clientSocket)
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
        var pod: V1Pod? = null
        try {
            // resolve once per connection: retries stay on the pod this client connected to
            val resolvedPod = state.resolvePod()
            pod = resolvedPod
            forwardWithRetries(clientSocket, resolvedPod, state, acceptedAtNanos)
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            logger.warn(
                "Could not port forward to ${describePod(pod)} " +
                    "using port ${state.localPort} -> ${state.remotePort}",
                e)
        } finally {
            state.clientSockets.remove(clientSocket)
            closeQuietly(clientSocket)
        }
    }

    /** Describes the connection's target for failure logs; [pod] is null when resolving it failed. */
    private fun describePod(pod: V1Pod?): String {
        val metadata = pod?.metadata
        val name = metadata?.name ?: return "an unresolved pod"
        val uid = metadata.uid
        return if (uid == null) "pod $name" else "pod $name (uid=$uid)"
    }

    private suspend fun CoroutineScope.forwardWithRetries(
        clientSocket: Socket,
        pod: V1Pod,
        state: ForwardState,
        acceptedAtNanos: Long
    ) {
        // wraps the client input so the client-gone probe can push back the byte it has to read (see isClientGone)
        val clientIn = PushbackInputStream(clientSocket.getInputStream(), 1)
        var firstFailureNanos: Long? = null
        for (attempt in 1..CONNECT_ATTEMPTS) {
            if (!isActive) return
            logger.info("Attempt #$attempt: Connecting ${state.localPort} -> ${state.remotePort}...")
            val isTerminal = when (
                val result = attemptForward(clientSocket, clientIn, pod, state)
            ) {
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
                    decideRetry(pod, failureStartedAt, attempt, state.ports, result.cause) !=
                        RetryDecision.RETRY
                }
            }
            if (isTerminal) break
            if (isClientGone(clientSocket, clientIn)) {
                logClientGone(state.localPort, state.remotePort)
                return
            }
            @Suppress("ConvertLongToDuration")
            delay(RECONNECT_DELAY)
            if (isClientGone(clientSocket, clientIn)) {
                logClientGone(state.localPort, state.remotePort)
                return
            }
        }
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

    private fun logClientGone(localPort: Int, remotePort: Int) {
        logger.info("Port forward handshake $localPort -> $remotePort: client disconnected; stopping retries")
    }

    /**
     * Runs one forwarding attempt and separates its two phases: a failing [PortForward.forward] call
     * ([AttemptResult.HandshakeFailed]) and an established connection whose copy finished
     * ([AttemptResult.Ended]).
     */
    private suspend fun attemptForward(
        clientSocket: Socket,
        clientIn: PushbackInputStream,
        pod: V1Pod,
        state: ForwardState
    ): AttemptResult {
        var forwardResult: PortForward.PortForwardResult? = null
        return try {
            val opened = openForward(pod, state)
            forwardResult = opened
            AttemptResult.Ended(copyStreams(clientSocket, clientIn, forwardResult, state.remotePort))
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            if (forwardResult == null) AttemptResult.HandshakeFailed(e)
            else AttemptResult.Ended(CopyResult.Failed(CopySide.REMOTE, e))
        } finally {
            closeStreams(state.remotePort, forwardResult)
        }
    }

    private fun openForward(pod: V1Pod, state: ForwardState): PortForward.PortForwardResult {
        val opened = PortForward(client).forward(pod, listOf(state.remotePort))
        logger.info("forward successful: ${state.localPort} -> ${state.remotePort}...")
        return opened
    }

    /**
     * Decides whether a failed handshake may be retried. The budget starts at the first failed
     * handshake, not at socket accept, so a slow handshake does not consume the whole budget.
     * Suspending: it awaits the liveness provider (a bounded HTTP call on [Dispatchers.IO]).
     */
    private suspend fun decideRetry(
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
        logHandshakeFailure(retry, liveness, attempt, ports, cause)
        return retry
    }

    private fun logHandshakeFailure(
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
                            "(podLiveness=$liveness); retrying in ${RECONNECT_DELAY}ms"
                )
            RetryDecision.POD_GONE ->
                logger.info(
                    "Port forward handshake $localPort -> $remotePort failed on attempt $attempt: ${cause.message} " +
                            "(podLiveness=$liveness); not retrying"
                )
            else ->
                logger.warn(
                    "Port forward $localPort -> $remotePort failed after $attempt attempt(s) " +
                            "(podLiveness=$liveness, lastCause=${cause.javaClass.simpleName}: ${cause.message}); giving up",
                    cause
                )
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
            "Port forward $localPort -> $remotePort ended (reason=${result.reason()}, " +
                "elapsed=${elapsedMs}ms); closing local socket"
        when (result) {
            is CopyResult.PeerClosed ->
                logger.info(message)
            is CopyResult.Failed ->
                logger.info(message, result.cause)
        }
    }

    private suspend fun copyStreams(
        clientSocket: Socket,
        clientIn: PushbackInputStream,
        forwardResult: PortForward.PortForwardResult,
        remotePort: Int
    ): CopyResult = coroutineScope {
        ensureActive()
        // completes once: first completion wins, the sibling pump's result is irrelevant
        // once the socket is closed below
        val firstResult = CompletableDeferred<CopyResult>()
        val podToClient = launch {
            firstResult.complete(runCopy(CopySide.REMOTE) {
                forwardResult.getInputStream(remotePort).copyToLocalSocket(clientSocket.getOutputStream())
            })
        }
        val clientToPod = launch {
            firstResult.complete(runCopy(CopySide.CLIENT) {
                clientIn.copyToWebSocketOutbound(forwardResult.getOutboundStream(remotePort))
            })
        }

        try {
            firstResult.await()
        } finally {
            // InputStream.read() ignores coroutine cancellation: closing the socket and the streams is what
            // unblocks the pumps. This must also run when this coroutine is cancelled.
            closeQuietly(clientSocket)
            closeStreams(remotePort, forwardResult)
            podToClient.cancel()
            clientToPod.cancel()
        }
    }

    private inline fun runCopy(side: CopySide, copy: () -> Unit): CopyResult =
        try {
            copy()
            CopyResult.PeerClosed(side)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CopyResult.Failed(side, e)
        }

    private fun closeStreams(port: Int, forwardResult: PortForward.PortForwardResult?) {
      // getInputStream/getOutboundStream can throw; isolate so one failure does not skip the other close
      runCatching { forwardResult?.getInputStream(port) }
          .onSuccess { closeQuietly(it) }
          .onFailure { logger.debug("Could not get input stream for port $port while closing port-forward", it) }
      runCatching { forwardResult?.getOutboundStream(port) }
          .onSuccess { closeQuietly(it) }
          .onFailure { logger.debug("Could not get outbound stream for port $port while closing port-forward", it) }
    }

    private fun applyClientSocketOptions(socket: Socket) {
        socket.tcpNoDelay = true
    }

    private fun InputStream.copyToLocalSocket(destination: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            destination.write(buffer, 0, n)
            destination.flush() // local socket ONLY
        }
    }

    private fun InputStream.copyToWebSocketOutbound(destination: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            destination.write(buffer, 0, n)
            // do NOT flush per chunk — client-java 24 WebSocketOutputStream.flush() sleeps 100ms
        }
        runCatching { destination.flush() }
    }

    @Throws(IOException::class)
    fun waitForForwardReady(port: Int) {
        val maxRetries = 30
        val retryDelay = 100L

        repeat(maxRetries) { attempt ->
            try {
                val testSocket = ServerSocket()
                testSocket.bind(InetSocketAddress("127.0.0.1", port))
                testSocket.close()
                // If we can bind to the port, it means port forwarding is not ready yet
                Thread.sleep(retryDelay)
            } catch (_: BindException) {
                // Port is already in use, which means port forwarding is ready
                return
            } catch (e: Exception) {
                if (attempt == maxRetries - 1) {
                    throw IOException("Port forwarding to local port $port is not ready after ${maxRetries * retryDelay}ms", e)
                }
                Thread.sleep(retryDelay)
            }
        }

        throw IOException("Port forwarding to local port $port is not ready after ${maxRetries * retryDelay}ms")
    }
}
