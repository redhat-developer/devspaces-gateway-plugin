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

import com.redhat.devtools.gateway.auth.config.AuthType
import com.redhat.devtools.gateway.auth.config.ServerConfig

internal fun getLocalServerConfig(type: AuthType): ServerConfig {
    return ServerConfig(
        callbackPath = if (type == AuthType.SSO_REDHAT) "${type.value}-callback" else "callback",
        externalUrl = if (type == AuthType.SSO_REDHAT) "http://localhost" else "http://127.0.0.1",
        port = null // dynamic
    )
}
