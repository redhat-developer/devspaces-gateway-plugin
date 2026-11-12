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
package com.redhat.devtools.gateway.devworkspace

/**
 * Signals a restart request the Gateway cannot execute: a missing, blank, malformed, or
 * unsupported devfile template payload.
 *
 * Thrown while reading the request, before the IDE is closed and before the workspace is
 * stopped, so a request the Gateway refuses never interrupts a running session.
 */
internal class InvalidRestartRequestException(
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)
