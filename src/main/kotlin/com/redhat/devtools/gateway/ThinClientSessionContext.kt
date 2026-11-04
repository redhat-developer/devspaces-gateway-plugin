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

import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.redhat.devtools.gateway.openshift.PortForwardSession
import com.redhat.devtools.gateway.server.RemoteIDEServer
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mutable state shared by everything that owns one thin-client session: the initial connect,
 * the port forwarder, and the pod-roll reconnect.
 *
 * Plain class, not a data class: the mutable fields would make data-class equals/hashCode
 * misleading — sessions compare by identity.
 */
@Suppress("UnstableApiUsage")
internal class ThinClientSessionContext(
    val localPort: Int,
    val remoteIdeServer: RemoteIDEServer,
    val onConnected: () -> Unit,
    val onDisconnected: () -> Unit,
    val onDevWorkspaceStopped: () -> Unit,
) {

    /** The session's port forwarder; assigned once running, see [DevSpacesConnection.connect]. */
    var forwarder: PortForwardSession? = null

    /** The session's current thin client handle, if any. */
    var thinClient: ThinClientHandle? = null

    /** True once the connect succeeded and the session is live. */
    val connectionLive: AtomicBoolean = AtomicBoolean(false)

    /** Set when the thin client failed to open or closed; ends the initial connect wait. */
    val connectFailed: AtomicBoolean = AtomicBoolean(false)

    /** Scope for recovery coroutines; assigned by [DevSpacesConnection.connect], cancelled on teardown. */
    var recoveryScope: CoroutineScope? = null

    /** True while a pod-roll reconnect owns the session; see [ThinClientReconnect]. */
    val reconnecting: AtomicBoolean = AtomicBoolean(false)

    /** Aborts an in-flight pod-roll reconnect; wired by [DevSpacesConnection.setupThinClientReconnect]. */
    var cancelReconnect: () -> Unit = {}

    /**
     * Throws when the in-flight pod-roll reconnect was canceled. Wired by [ThinClientReconnect]
     * for the duration of a reconnect so waits inside [DevSpacesConnection.startThinClientOnReconnect]
     * (mirror / [DevSpacesConnection.waitForConnect]) stop promptly.
     */
    var throwIfReconnectCanceled: () -> Unit = {}

    /**
     * Handles closed by the reconnect itself. Registered *before* the close so that
     * [DevSpacesConnection.onThinClientClosed] ignores the resulting close events, whenever
     * they fire — a stale event must not tear down a session that just reconnected.
     */
    val handlesClosedByReconnect: MutableSet<ThinClientHandle> = ConcurrentHashMap.newKeySet()

    /**
     * Reports whether a close event for [handle] must be ignored, and consumes the
     * reconnect-close registration while doing so (close events fire at most meaningfully
     * once per handle, so the set does not grow unboundedly).
     *
     * True when either the reconnect closed [handle] itself, or [handle] is a stale handle
     * the reconnect already replaced (it is no longer the session's client) — repeat events
     * for such handles must not tear down the session, whenever they fire.
     */
    fun consumeReconnectOwnedClose(handle: ThinClientHandle): Boolean =
        handlesClosedByReconnect.remove(handle) || thinClient !== handle
}
