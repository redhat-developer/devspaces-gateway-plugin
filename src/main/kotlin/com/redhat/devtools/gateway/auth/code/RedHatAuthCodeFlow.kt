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

import com.nimbusds.jwt.JWTParser
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.redhat.devtools.gateway.util.IdeHttpProxy
import com.nimbusds.openid.connect.sdk.AuthenticationRequest
import com.nimbusds.openid.connect.sdk.Nonce
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * RedHat SSO OAuth Flow.
 * Creates and returns a Service Account pipeline token limited only for Sandboxed clusters
 */
class RedHatAuthCodeFlow(
    private val clientId: String,
    private val redirectUri: URI,
    private val providerMetadata: OIDCProviderMetadata
) : AuthCodeFlow {

    private lateinit var codeVerifier: CodeVerifier
    private lateinit var nonce: Nonce
    private lateinit var state: State

    private val httpClient by lazy {
        IdeHttpProxy.configure(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
        ).build()
    }

    override suspend fun startAuthFlow(): AuthCodeRequest {
        codeVerifier = CodeVerifier()
        nonce = Nonce()
        state = State()

        val request = AuthenticationRequest.Builder(
                ResponseType.CODE,
                Scope("openid", "profile", "email"),
                ClientID(clientId),
                redirectUri
            )
            .endpointURI(providerMetadata.authorizationEndpointURI)
            .codeChallenge(codeVerifier, CodeChallengeMethod.S256)
            .nonce(nonce)
            .state(state)
            .build()

        return AuthCodeRequest(
            authorizationUri = request.toURI(),
            codeVerifier = codeVerifier,
            nonce = nonce
        )
    }

    override suspend fun handleCallback(parameters: Parameters): SSOToken {
        val code = parameters["code"] ?: error("Missing 'code' parameter in callback")
        val form = encodeForm(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "redirect_uri" to redirectUri.toString(),
            "code_verifier" to codeVerifier.value
        )
        val basicAuth = "Basic " + Base64.getEncoder()
            .encodeToString("$clientId:".toByteArray(StandardCharsets.UTF_8))
        val token = httpClient.sendPostRequest(
            providerMetadata.tokenEndpointURI.toString(),
            basicAuth,
            form,
            errorPrefix = "Token request failed"
        )
        return createSSOToken(token)
    }

    private fun createSSOToken(token: AccessTokenResponseJson): SSOToken {
        val expiresInSeconds = if (token.expiresIn > 0) token.expiresIn else 3600L
        val accountLabel = createAccountLabel(token.idToken)
        return SSOToken(
            accessToken = token.accessToken,
            idToken = token.idToken,
            expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000,
            accountLabel = accountLabel
        )
    }

    override suspend fun login(parameters: Parameters): SSOToken =
        error(
            "Direct login is not supported for Red Hat SSO authentication. " +
                    "This flow requires browser-based authentication via startAuthFlow(), " +
                    "followed by token exchange with the Sandbox API."
        )

    private fun createAccountLabel(idToken: String): String = if (idToken.isNotBlank()) {
        try {
            val jwt = JWTParser.parse(idToken) as SignedJWT
            val claims = jwt.jwtClaimsSet

            claims.getStringClaim("preferred_username")
                ?: claims.getStringClaim("email")
                ?: "unknown-user"
        } catch (e: Exception) {
            "unknown-user"
        }
    } else {
        "unknown-user"
    }

    private fun encodeForm(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }

}
