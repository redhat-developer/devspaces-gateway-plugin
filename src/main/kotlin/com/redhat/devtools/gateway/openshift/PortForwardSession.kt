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

/**
 * Running port forward session.
 *
 * - [localPort]: loopback port; session self-describes, no port passed alongside
 * - [close]: stops listener + closes connections → client sees EOF
 * - [dropClientConnections]: closes the copied connections, keeps listening → client sees EOF now,
 *   a later re-dial still reaches whatever pod serves the workspace then (CRW-13910 stop/restart)
 * - listener must not outlive session: prevents re-dial after teardown
 * - forward may retarget to replacement pod → re-dial hits current pod
 */
internal class PortForwardSession(
    val localPort: Int,
    /** Shared with retry loops; fresh default for handle-only callers (tests, fakes). */
    private val phase: SessionPhase = SessionPhase(),
    /** Cuts the copied client connections; no-op default for handle-only callers (tests, fakes). */
    private val dropClients: () -> Unit = {},
    private val stopForward: () -> Unit
) : ForwardDials, Closeable {
    /** Guest up? See [SessionPhase.isEstablished]. */
    val isEstablished: Boolean
        get() = phase.isEstablished

    /** [System.nanoTime] of last successful handshake. See [SessionPhase]. */
    val lastSuccessfulHandshakeNanos: Long?
        get() = phase.lastSuccessfulHandshakeNanos

    /** Handshake succeeded, copy still running? See [SessionPhase]. */
    val hasActiveForward: Boolean
        get() = phase.hasActiveForward

    override val isClientDialed: Boolean
        get() = phase.isClientDialed

    /** [System.nanoTime] when last failed dial ended. See [SessionPhase]. */
    val lastFailedDialNanos: Long?
        get() = phase.lastFailedDialNanos

    override val oldestActiveHandshakeNanos: Long?
        get() = phase.oldestActiveHandshakeNanos

    override val lastLongLivedDialNanos: Long?
        get() = phase.lastLongLivedDialNanos

    override val failedDials: Long
        get() = phase.failedDials

    /** Switch retries to steady-state policy; call once Guest up. Idempotent. */
    fun markEstablished() = phase.markEstablished()

    /**
     * Closes every copied client connection so the Guest reads EOF now, but keeps listening.
     *
     * Used when the workspace stops: the loss must show without waiting for the pod to terminate,
     * yet a stopped workspace may be started again (CRW-13910) — recovery must stay able to
     * reconnect the Guest, and the re-dial of its forced reconnect must still reach the pod that
     * serves the workspace then. A cut copy ends like a pod-side loss, so it starts the recovery
     * episode instead of bypassing it.
     */
    fun dropClientConnections() = dropClients()

    override fun close() = stopForward()
}

/**
 * Opens port forward to pod.
 *
 * - function type = seam: lifecycle/recovery testable without cluster
 * - forwarder picks + binds free loopback port itself → no allocation race
 * - returned session already listening
 * - handshake fails + [pod] gone → [resolvePod] may name replacement pod, forward retargets
 * - `null` or same-pod resolution = terminal "pod gone" (behaves pinned to [pod])
 *
 * @param pod pod to forward to
 * @param remotePort port on [pod] to bridge
 * @param resolvePod current running+ready pod; may block on API server → retry loop only, never caller thread
 * @param onRemoteEnd invoked on non-clean client close: pod-side end (EOF/failure) or copy failure; not on client close
 */
internal typealias OpenPortForward = (
    pod: V1Pod,
    remotePort: Int,
    resolvePod: () -> V1Pod?,
    onRemoteEnd: () -> Unit
) -> PortForwardSession

/**
 * "Can a port forward to [remotePort] on [pod] be established now?"
 *
 * - real forward, opened then closed at once: no listener, no client socket
 * - proves full path (auth, API server, pod, listener) — unlike pod liveness query
 * - bounded: hanging open = `false` (hanging API server can't serve Guest either)
 */
internal typealias ProbePortForward = suspend (pod: V1Pod, remotePort: Int) -> Boolean
