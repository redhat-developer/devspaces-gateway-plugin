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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.Socket

/** Which pump of a forwarded connection ended it: the pod (remote) or the local client. */
internal enum class CopySide { REMOTE, CLIENT }

/** Why a bidirectional stream copy ended, and which side ended it. */
internal sealed interface CopyResult {
    val side: CopySide

    /** Whether the client ended the connection cleanly itself: the Guest closed and is already gone. */
    val isClosedByClient: Boolean
        get() = this is PeerClosed && side == CopySide.CLIENT

    /** The pump reached EOF: that side closed the connection normally. */
    data class PeerClosed(override val side: CopySide) : CopyResult

    /** The pump failed while copying. */
    data class Failed(override val side: CopySide, val cause: Throwable) : CopyResult
}

/**
 * Outcome of a single forwarding attempt, split by phase.
 *
 * Only [HandshakeFailed] is retryable: once the streams are copying, the local socket is closed when
 * the copy ends, so a retry on the same socket cannot succeed.
 */
internal sealed interface AttemptResult {
    /** [PortForward.forward] threw: no streams were established. */
    data class HandshakeFailed(val cause: Throwable) : AttemptResult

    /** The streams were established and the copy already finished. */
    data class Ended(val copy: CopyResult) : AttemptResult
}

/**
 * Upper bound of one port-forward open.
 *
 * client-java's `PortForward.forward` ends with `WebSocketStreamHandler.waitForInitialized()`, an
 * unbounded `Object.wait()` until the port-forward websocket initializes. When that websocket
 * stalls - observed right after an idea-server process was killed - the open never returns: the
 * dial hangs inside attempt #1, so the retry policy, the client-gone probe and the dial's end are
 * all behind a blocked call, and a live `isClientDialed` never clears. Healthy opens take 0.3-0.6 s;
 * 10 s bounds the hang with headroom for a slow cluster.
 *
 * The open is made interruptible ([runInterruptible]): `Object.wait` responds to
 * `Thread.interrupt`, so the bound actually frees the thread. Accepted trade-off: an interrupted
 * open can leave its half-initialized websocket unclosed, because client-java hands out no handle
 * for an open that never completed - a rare, bounded leak instead of an eternal hang.
 */
internal const val OPEN_TIMEOUT_MS: Long = 10_000

/**
 * Runs a single forwarding attempt against a pod: opens the port forward, pumps both directions
 * until one side ends the connection, and closes the streams afterwards.
 */
internal class ForwardAttempt(
    client: ApiClient,
    /**
     * Upper bound of one open; see [OPEN_TIMEOUT_MS]. Overridable so tests can exercise the bound
     * with a short hang instead of waiting the production timeout out.
     */
    private val openTimeoutMs: Long = OPEN_TIMEOUT_MS,
    /**
     * Opens the tunnel to a pod port. Overridable so a test can block it without mocking
     * client-java: the bound works by interrupting the blocked thread, and interrupting a mocked
     * call corrupts mockk's global state, which would poison every later test in the class.
     */
    private val openForward: (V1Pod, Int) -> PortForward.PortForwardResult =
        { pod, remotePort -> PortForward(client).forward(pod, listOf(remotePort)) }
) {

    companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
    }

    private val logger = logger<ForwardAttempt>()

    /**
     * [onHandshakeSuccess] is invoked right after [PortForward.forward] succeeds and before the
     * streams are copied: it reports "the handshake worked" separately from "the copy ended",
     * which the session needs to know when its last successful handshake was.
     */
    suspend fun attempt(
        pod: V1Pod,
        ports: PortMapping,
        clientSocket: Socket,
        clientIn: PushbackInputStream,
        onHandshakeSuccess: () -> Unit = {}
    ): AttemptResult {
        var forwardResult: PortForward.PortForwardResult? = null
        return try {
            val opened = openForward(pod, ports)
            forwardResult = opened
            onHandshakeSuccess()
            AttemptResult.Ended(copyStreams(clientSocket, clientIn, opened, ports.remotePort))
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            if (forwardResult == null) AttemptResult.HandshakeFailed(e)
            else AttemptResult.Ended(CopyResult.Failed(CopySide.REMOTE, e))
        } finally {
            closeStreams(ports.remotePort, forwardResult)
        }
    }

    private suspend fun openForward(pod: V1Pod, ports: PortMapping): PortForward.PortForwardResult {
        val opened = openForwardTo(pod, ports.remotePort)
        logger.info("forward successful: ${ports.localPort} -> ${ports.remotePort}...")
        return opened
    }

    /**
     * Opens a forward without copying anything and closes it at once: answers "is the path usable
     * right now?" without a client socket or listener. The caller bounds the call; this method just
     * classifies the open.
     */
    @Suppress("TooGenericExceptionCaught") // any failure to open the tunnel is a failed probe
    suspend fun tryOpen(pod: V1Pod, remotePort: Int): Boolean {
        var forwardResult: PortForward.PortForwardResult? = null
        return try {
            forwardResult = openForwardTo(pod, remotePort)
            logger.info("Port forward probe succeeded: pod=${pod.metadata?.name}, port=$remotePort")
            true
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            logger.info(
                "Port forward probe failed: pod=${pod.metadata?.name}, port=$remotePort, " +
                    "cause=${e.javaClass.simpleName}: ${e.message}"
            )
            false
        } finally {
            closeStreams(remotePort, forwardResult)
        }
    }

    /**
     * Opens the forward, bounded to [openTimeoutMs] and interruptible, so a stalled websocket
     * initialization cannot pin the dial ([OPEN_TIMEOUT_MS]).
     *
     * A bound that expires is a *failed handshake*: the connection could not be established, so the
     * retry loop may try again. It is not the dial's cancellation, which must keep propagating (the
     * session is closing). [runInterruptible] interrupts the blocked thread, which is what makes
     * `waitForInitialized` return at all.
     */
    private suspend fun openForwardTo(pod: V1Pod, remotePort: Int): PortForward.PortForwardResult =
        try {
            withTimeout(openTimeoutMs) {
                runInterruptible(Dispatchers.IO) {
                    openForward(pod, remotePort)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw openFailure(e, "timed out after $openTimeoutMs ms")
        } catch (e: InterruptedException) {
            throw openFailure(e, "was interrupted before the stream initialized")
        }

    /**
     * Classifies a failed bounded open. The dial being cancelled is not a failure of the attempt and
     * keeps propagating; anything else is a retryable [IOException].
     */
    private suspend fun openFailure(cause: Exception, reason: String): Exception =
        if (currentCoroutineContext().isActive) {
            IOException("Port forward open $reason", cause)
        } else {
            CancellationException("Port forward open cancelled")
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
}
