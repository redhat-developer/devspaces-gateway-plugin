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
package com.redhat.devtools.gateway.auth.config

data class ServerConfig(
    /**
     * Path relative to externalUrl
     * Example: sso-redhat-callback
     */
    val callbackPath: String,

    /**
     * Fully qualified external base URL
     * Examples:
     * - http://localhost
     * - https://workspace-id.openshiftapps.com
     */
    val externalUrl: String,

    /**
     * Local listening port (optional, dynamic if null)
     */
    val port: Int? = null
)
