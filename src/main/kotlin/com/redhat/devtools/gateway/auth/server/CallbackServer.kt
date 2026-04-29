/*
 * Copyright (c) 2025-2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.auth.server

import com.redhat.devtools.gateway.auth.code.Parameters

interface CallbackServer {

    /** Starts the server and registers a callback handler and returns the port it's bound to */
    suspend fun start(): Int

    /** Stops the server */
    suspend fun stop()

    /** Wait for server receives the Parameters or cancelled */
    suspend fun awaitCallback(timeoutMs: Long): Parameters?
}
