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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.*
import javax.net.ssl.SSLContext

/**
 * Canonical OpenShift OAuth flow (PKCE + Authorization Code), mimics `oc login --web`.
 * Does NOT require RH SSO token.
 */
class OpenShiftAuthCodeFlow(
    private val apiServerUrl: String,        // Cluster API server
    private val redirectUri: URI?,           // Local callback server URI (optional)
    private val sslContext: SSLContext
) : AuthCodeFlow {

    private lateinit var codeVerifier: CodeVerifier
    private lateinit var state: State

    private  lateinit var metadata: OAuthMetadata

    private val json = Json { ignoreUnknownKeys = true }

    private val discoveryClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .sslContext(sslContext)
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private val noRedirectClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .sslContext(sslContext)
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

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
        val client = discoveryClient

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$apiServerUrl/.well-known/oauth-authorization-server"))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
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

        return exchangeCodeForToken(code)
    }

    private fun encodeForm(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=" +
                    URLEncoder.encode(v, StandardCharsets.UTF_8)
        }

    private suspend fun exchangeCodeForToken(code: String): SSOToken {
        val httpClient = discoveryClient

        val basicAuth = "Basic " + Base64.getEncoder()
            .encodeToString("openshift-cli-client:".toByteArray(StandardCharsets.UTF_8))

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
        val expiresAt =
            if (token.expiresIn > 0) System.currentTimeMillis() + token.expiresIn * 1000 else null

        return SSOToken(
            accessToken = token.accessToken,
            idToken = "",
            accountLabel = "openshift-user",
            expiresAt = expiresAt
        )
    }

    override suspend fun login(parameters: Parameters): SSOToken {
        val username = parameters["username"] ?: error("Missing 'username'")
        val password = parameters["password"] ?: error("Missing 'password'")

        metadata = discoverOAuthMetadata()
        codeVerifier = CodeVerifier()
        state = State()

        val httpClient = noRedirectClient

        val redirectUri = URI(
            metadata.tokenEndpoint.replace(
                "/oauth/token",
                "/oauth/token/implicit"
            )
        )

        val authorizeUri = AuthorizationRequest.Builder(
                ResponseType.CODE,
                ClientID("openshift-challenging-client")
            )
            .endpointURI(URI(metadata.authorizationEndpoint))
            .redirectionURI(redirectUri)
            .build()
            .toURI()

        val basicAuth = "Basic " + Base64.getEncoder()
            .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))

        // First request (expect 401)
        var request = HttpRequest.newBuilder()
            .uri(authorizeUri)
            .header("X-Csrf-Token", "1")
            .GET()
            .build()

        var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())

        // Retry with Basic auth
        if (response.statusCode() == 401) {
            request = HttpRequest.newBuilder()
                .uri(authorizeUri)
                .header("Authorization", basicAuth)
                .header("X-Csrf-Token", "1")
                .GET()
                .build()

            response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
        }

        if (response.statusCode() !in listOf(302, 303)) {
            error("Authorization failed: ${response.statusCode()}")
        }

        val location = response.headers().firstValue("Location")
            .orElseThrow { error("Missing redirect Location header") }
        val redirectedUri = URI(location)
        val query = redirectedUri.query ?: error("Missing query in redirect")
        val params = query.split("&")
            .map { it.split("=", limit = 2) }
            .associate { it[0] to URLDecoder.decode(it[1], StandardCharsets.UTF_8) }

        val code = params["code"] ?: error("Authorization code not found in redirect")

        val token = exchangeCodeForTokenWithBasicAuth(httpClient, code = code, redirectUri = redirectUri)

        return SSOToken(
            accessToken = token.accessToken,
            idToken = token.idToken,
            accountLabel = username,
            expiresAt = token.expiresAt
        )
     }

    private suspend fun exchangeCodeForTokenWithBasicAuth(
        httpClient: HttpClient,
        code: String,
        redirectUri: URI
    ): SSOToken {
        val clientAuth = "Basic " + Base64.getEncoder()
            .encodeToString("openshift-challenging-client:".toByteArray(StandardCharsets.UTF_8))

        val form = encodeForm(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri.toString(),
            "code_verifier" to codeVerifier.value
        )

        val request = HttpRequest.newBuilder()
            .uri(URI(metadata.tokenEndpoint))
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", clientAuth)
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            error("Token exchange failed: ${response.statusCode()} ${response.body()}")
        }

        val token = json.decodeFromString(
            AccessTokenResponseJson.serializer(),
            response.body()
        )
        val expiresAt = if (token.expiresIn > 0) System.currentTimeMillis() + token.expiresIn * 1000 else null

        return SSOToken(
            accessToken = token.accessToken,
            idToken = "",
            accountLabel = "",
            expiresAt = expiresAt
        )
    }
}
