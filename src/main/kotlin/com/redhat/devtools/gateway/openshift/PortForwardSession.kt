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
package com.redhat.devtools.gateway.openshift

import io.kubernetes.client.openapi.models.V1Pod
import java.io.Closeable
import java.io.IOException

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

    override val isClientDialed: Boolean
        get() = phase.isClientDialed

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
