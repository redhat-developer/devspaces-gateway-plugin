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

/**
 * Join-link port handling shared by the initial connect and the pod-roll reconnect.
 *
 * The remote IDE server listens on [REMOTE_IDE_PORT] inside the workspace pod; the session
 * forwards that port to a local one, so every join link handed to a thin client must be
 * rewritten before use.
 */
internal object ThinClientJoinLink {

    /** Port that the remote IDE server listens on inside the workspace pod. */
    const val REMOTE_IDE_PORT: Int = 5990

    private val REMOTE_IDE_PORT_TOKEN: String = ":$REMOTE_IDE_PORT"

    /** Rewrites the remote IDE port in [joinLink] to the session's [localPort]. */
    fun withLocalPort(joinLink: String, localPort: Int): String =
        joinLink.replace(REMOTE_IDE_PORT_TOKEN, ":$localPort")
}
