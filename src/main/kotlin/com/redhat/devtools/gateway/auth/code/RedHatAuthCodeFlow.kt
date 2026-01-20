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
import com.nimbusds.openid.connect.sdk.AuthenticationRequest
import com.nimbusds.openid.connect.sdk.Nonce
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import com.redhat.devtools.gateway.auth.server.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.*

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

    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

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

        fun encodeForm(vararg pairs: Pair<String, String>): String =
            pairs.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
            }

        val form = encodeForm(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "redirect_uri" to redirectUri.toString(),
            "code_verifier" to codeVerifier.value
        )

        val basicAuth = "Basic " + Base64.getEncoder()
            .encodeToString("$clientId:".toByteArray(StandardCharsets.UTF_8))

        val request = HttpRequest.newBuilder()
            .uri(providerMetadata.tokenEndpointURI)
            .header("Authorization", basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Token request failed: ${response.statusCode()}\n${response.body()}")
        }

        val json = Json { ignoreUnknownKeys = true }
        val body = json.parseToJsonElement(response.body()).jsonObject

        val accessToken = body["access_token"]?.jsonPrimitive?.content
            ?: error("Missing access_token in token response")

        val idToken = body["id_token"]?.jsonPrimitive?.content.orEmpty()
        val expiresInSeconds = body["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
        val accountLabel = if (idToken.isNotBlank()) {
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

        return SSOToken(
            accessToken = accessToken,
            idToken = idToken,
            expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000,
            accountLabel = accountLabel
        )
    }
}
