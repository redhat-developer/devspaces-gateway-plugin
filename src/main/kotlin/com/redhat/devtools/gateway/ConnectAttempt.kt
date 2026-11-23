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
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.openshift.PortForwardSession
import com.redhat.devtools.gateway.server.RemoteIDEServer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One connect attempt: the state its thin-client close callback and teardown share.
 *
 * Per attempt by design. The close callback fires on another thread and may fire late or twice, so
 * holding this state on the connection would let a stale callback end the wait, or tear down a
 * later attempt. Isolating it keeps stale signals harmless.
 *
 * [isSessionLive], [connectWaitEnded] and [tearDownStarted] live here for the same reason: they
 * describe one attempt, not the connection.
 */
internal class ConnectAttempt(
    val workspace: DevWorkspace,
    val onConnectionEnded: () -> Unit,
    val onDevWorkspaceStopped: () -> Unit
) {

    private val liveSession = AtomicBoolean(false)

    /** Set once the handshake completed: tells the close callback whether it owns teardown. */
    fun setSessionLive(isLive: Boolean) {
        liveSession.set(isLive)
    }

    /** Whether the handshake completed, i.e. the close callback owns teardown. */
    fun isSessionLive(): Boolean = liveSession.get()

    /** Set by the close callback: ends the connect wait, whether it failed or not. */
    val connectWaitEnded = AtomicBoolean(false)

    /** Ensures teardown runs at most once for this attempt. */
    val tearDownStarted = AtomicBoolean(false)

    /** IDE server of the attempt; set once the workspace is ready. */
    var remoteIdeServer: RemoteIDEServer? = null

    /** Port forward of the attempt; set once the forward is established. */
    var forwarder: PortForwardSession? = null

    /** Started thin client; set before its close callbacks are registered. */
    var thinClient: ThinClientHandle? = null
}
