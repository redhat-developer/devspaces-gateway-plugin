/*
 * Copyright (c) 2024-2025 Red Hat, Inc.
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

import com.redhat.devtools.gateway.server.RemoteIDEServer
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal data class ThinClientSessionContext(
    val localPort: Int,
    val remoteIdeServer: RemoteIDEServer,
    var forwarder: Closeable?,
    val onConnected: () -> Unit,
    val onDisconnected: () -> Unit,
    val onDevWorkspaceStopped: () -> Unit,
    val checkCancelled: (() -> Unit)?,
    val reconnecting: AtomicBoolean = AtomicBoolean(false),
    /** Set when the user explicitly disconnects from Gateway; see [DevSpacesConnection.onClientClosed]. */
    val intentionalDisconnect: AtomicBoolean = AtomicBoolean(false),
)
