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
package com.redhat.devtools.gateway.auth.code

import java.net.URI
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.openid.connect.sdk.Nonce
import com.redhat.devtools.gateway.auth.server.Parameters
import kotlinx.serialization.Serializable

/**
 * Represents the data needed to start the PKCE + Auth Code flow.
 */
data class AuthCodeRequest(
    val authorizationUri: URI,   // URL to open in browser
    val codeVerifier: CodeVerifier, // Used for token exchange
    val nonce: Nonce               // Anti-replay / OIDC nonce
)

/**
 * Represents the SSO Token
 */
data class SSOToken(
    val accessToken: String,
    val idToken: String,
    val accountLabel: String,
    val expiresAt: Long? = null
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt?.let { now >= it } ?: false
}

/**
 * Represents the final result after exchanging code for tokens.
 */
enum class AuthTokenKind {
    SSO,
    TOKEN,
    PIPELINE
}

@Serializable
data class TokenModel(
    val accessToken: String,
    val expiresAt: Long?,          // null = non-expiring (pipeline)
    val accountLabel: String,
    val kind: AuthTokenKind,
    val clusterApiUrl: String,
    val namespace: String? = null,
    val serviceAccount: String? = null
)

interface AuthCodeFlow {
    /** Starts the auth flow and returns the info to open the browser */
    suspend fun startAuthFlow(): AuthCodeRequest

    /** Handles the redirect/callback and returns the final tokens */
    suspend fun handleCallback(parameters: Parameters): SSOToken
}
