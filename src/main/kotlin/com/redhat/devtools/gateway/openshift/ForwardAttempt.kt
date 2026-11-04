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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
 * Runs a single forwarding attempt against a pod: opens the port forward, pumps both directions
 * until one side ends the connection, and closes the streams afterwards.
 */
internal class ForwardAttempt(private val client: ApiClient) {

    companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
    }

    private val logger = logger<ForwardAttempt>()

    suspend fun attempt(
        pod: V1Pod,
        ports: PortMapping,
        clientSocket: Socket,
        clientIn: PushbackInputStream
    ): AttemptResult {
        var forwardResult: PortForward.PortForwardResult? = null
        return try {
            val opened = openForward(pod, ports)
            forwardResult = opened
            AttemptResult.Ended(copyStreams(clientSocket, clientIn, opened, ports.remotePort))
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            if (forwardResult == null) AttemptResult.HandshakeFailed(e)
            else AttemptResult.Ended(CopyResult.Failed(CopySide.REMOTE, e))
        } finally {
            closeStreams(ports.remotePort, forwardResult)
        }
    }

    private fun openForward(pod: V1Pod, ports: PortMapping): PortForward.PortForwardResult {
        val opened = PortForward(client).forward(pod, listOf(ports.remotePort))
        logger.info("forward successful: ${ports.localPort} -> ${ports.remotePort}...")
        return opened
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
