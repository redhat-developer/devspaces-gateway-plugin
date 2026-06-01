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
package com.redhat.devtools.gateway.auth.session

import kotlin.Exception

sealed class SsoLoginException(message: String) : Exception(message) {
    class Timeout : SsoLoginException("Login timed out")
    class Failed(message: String) : SsoLoginException(message)
    class Cancelled : SsoLoginException("Login cancelled")
}
