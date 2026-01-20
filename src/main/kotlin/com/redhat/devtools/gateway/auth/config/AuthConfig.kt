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

data class AuthConfig(
    val serviceId: String = "redhat-account-auth",

    val authUrl: String =
        System.getenv("REDHAT_SSO_URL")
            ?: "https://sso.redhat.com/auth/realms/redhat-external/",

    val apiUrl: String =
        System.getenv("KAS_API_URL")
            ?: "https://api.openshift.com",

    val clientId: String =
        System.getenv("CLIENT_ID")
            ?: "vscode-redhat-account",

    val deviceCodeOnly: Boolean =
        System.getenv("DEVICE_CODE_ONLY")
            ?.equals("true", ignoreCase = true)
            ?: false,

    val authType: AuthType = AuthType.SSO_REDHAT
)
