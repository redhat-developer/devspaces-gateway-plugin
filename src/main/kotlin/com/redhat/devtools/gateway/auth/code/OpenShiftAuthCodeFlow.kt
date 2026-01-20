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

import com.nimbusds.oauth2.sdk.AuthorizationRequest
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.openid.connect.sdk.Nonce
import com.redhat.devtools.gateway.auth.server.Parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Canonical OpenShift OAuth flow (PKCE + Authorization Code), mimics `oc login --web`.
 * Does NOT require RH SSO token.
 */
class OpenShiftAuthCodeFlow(
    private val apiServerUrl: String,        // Cluster API server
    private val redirectUri: URI             // Local callback server URI
) : AuthCodeFlow {

    private lateinit var codeVerifier: CodeVerifier
    private lateinit var state: State

    private  lateinit var metadata: OAuthMetadata

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Serializable
    private data class OAuthMetadata(
        val issuer: String,

        @SerialName("authorization_endpoint")
        val authorizationEndpoint: String,

        @SerialName("token_endpoint")
        val tokenEndpoint: String
    )

    /**
     * Discover OAuth endpoints from the cluster.
     */
    private suspend fun discoverOAuthMetadata(): OAuthMetadata {
        val url = "$apiServerUrl/.well-known/oauth-authorization-server"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("OAuth discovery failed: ${response.statusCode()}\n${response.body()}")
        }

        return json.decodeFromString(OAuthMetadata.serializer(), response.body())
    }

    override suspend fun startAuthFlow(): AuthCodeRequest {
        metadata = discoverOAuthMetadata()
        codeVerifier = CodeVerifier()
        state = State()

        val request = AuthorizationRequest.Builder(
                ResponseType.CODE,
                ClientID("openshift-cli-client") // same as oc
            )
            .endpointURI(URI(metadata.authorizationEndpoint))
            .redirectionURI(redirectUri)
            .codeChallenge(codeVerifier, CodeChallengeMethod.S256)
            .build()

        return AuthCodeRequest(
            authorizationUri = request.toURI(),
            codeVerifier = codeVerifier,
            nonce = Nonce()
        )
    }

    @Serializable
    data class AccessTokenResponseJson(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long
    )

    override suspend fun handleCallback(parameters: Parameters): SSOToken {
        val code: String = parameters["code"] ?: error("Missing 'code' parameter in callback")

        val basicAuth = "Basic " + Base64.getEncoder()
            .encodeToString("openshift-cli-client:".toByteArray(StandardCharsets.UTF_8))

        fun encodeForm(vararg pairs: Pair<String, String>): String =
            pairs.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
            }

        val form = encodeForm(
            "grant_type" to "authorization_code",
            "client_id" to "openshift-cli-client",
            "code" to code,
            "redirect_uri" to redirectUri.toString(),
            "code_verifier" to codeVerifier.value
        )

        val request = HttpRequest.newBuilder()
            .uri(URI(metadata.tokenEndpoint))
            .header("Authorization", basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Token request failed: ${response.statusCode()}\n${response.body()}")
        }

        val token = json.decodeFromString(AccessTokenResponseJson.serializer(), response.body())
        val expiresAt = if (token.expiresIn > 0) System.currentTimeMillis() + token.expiresIn * 1000 else null

        return SSOToken(
            accessToken = token.accessToken,
            idToken = "", // OpenShift does not issue id_token
            accountLabel = "openshift-user",
            expiresAt = expiresAt
        )
    }
}
