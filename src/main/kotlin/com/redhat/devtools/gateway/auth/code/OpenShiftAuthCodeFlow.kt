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
import com.intellij.openapi.diagnostic.logger
import com.redhat.devtools.gateway.util.toServerBaseUrl
import kotlinx.coroutines.future.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.lang.Void
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

    private val logger = logger<OpenShiftAuthCodeFlow>()

    private lateinit var codeVerifier: CodeVerifier
    private lateinit var state: State

    private  lateinit var metadata: OAuthMetadata

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

    companion object {
        private val logger = logger<OpenShiftAuthCodeFlow>()
        private val json = Json { ignoreUnknownKeys = true }

        /** OAuth HTTP endpoint base URLs discovered from the API server. */
        suspend fun discoverOAuthEndpointBaseUrls(
            apiServerUrl: String,
            sslContext: SSLContext,
        ): List<String> {
            val discoveryUrl = "$apiServerUrl/.well-known/oauth-authorization-server"
            logger.info("TLS trust: discovering OAuth endpoints from $discoveryUrl")
            val client = HttpClient.newBuilder()
                .sslContext(sslContext)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()

            val response = try {
                sendGetRequest(client, discoveryUrl, "OAuth discovery failed")
            } catch (e: Exception) {
                logger.error("TLS trust: OAuth discovery request to $discoveryUrl failed", e)
                throw e
            }
            val metadata = json.decodeFromString(OAuthMetadata.serializer(), response.body())
            val urls = listOf(metadata.tokenEndpoint, metadata.authorizationEndpoint)
                .map { URI(it).toServerBaseUrl() }
                .distinct()
            logger.info(
                "TLS trust: OAuth discovery succeeded (issuer=${metadata.issuer}, " +
                    "endpoints=${urls.joinToString()})"
            )
            return urls
        }

        private suspend fun sendGetRequest(httpClient: HttpClient, url: String, errorPrefix: String = "Request failed"): HttpResponse<String> {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build()

            val response = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
            ).await()
            if (response.statusCode() !in 200..299) {
                error("$errorPrefix: ${response.statusCode()}\n${response.body()}")
            }
            return response
        }

        private suspend fun sendPostRequest(
            httpClient: HttpClient,
            url: String,
            authHeader: String,
            formBody: String,
            errorPrefix: String = "Request failed"
        ): AccessTokenResponseJson {
            val request = HttpRequest.newBuilder()
                .uri(URI(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build()

            val response = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
            ).await()
            if (response.statusCode() !in 200..299) {
                error("$errorPrefix: ${response.statusCode()}\n${response.body()}")
            }

            return json.decodeFromString(AccessTokenResponseJson.serializer(), response.body())
        }
    }

    /**
     * Discover OAuth endpoints from the cluster.
     */
    private suspend fun discoverOAuthMetadata(): OAuthMetadata {
        val response = sendGetRequest(discoveryClient, "$apiServerUrl/.well-known/oauth-authorization-server")
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
        val uri = redirectUri ?: error("redirectUri is required for code exchange")
        return exchangeCodeForToken(code, discoveryClient, "openshift-cli-client", uri, accountLabel = "openshift-user")
    }

    private fun encodeForm(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=" +
                    URLEncoder.encode(v, StandardCharsets.UTF_8)
        }

    private fun parseRedirectQuery(location: String): Map<String, String> {
        val query = URI(location).query ?: error("Missing query in redirect")
        return query.split("&")
            .map { it.split("=", limit = 2) }
            .associate { it[0] to URLDecoder.decode(it[1], StandardCharsets.UTF_8) }
    }

    private suspend fun exchangeCodeForToken(
        code: String,
        client: HttpClient,
        clientId: String,
        redirectUri: URI,
        clientIdInForm: Boolean = true,
        accountLabel: String = "",
    ): SSOToken {
        val authHeader = "Basic " + Base64.getEncoder()
            .encodeToString("$clientId:".toByteArray(StandardCharsets.UTF_8))

        val form = encodeForm(
            "grant_type" to "authorization_code",
            "code" to code,
            "code_verifier" to codeVerifier.value,
            "redirect_uri" to redirectUri.toString(),
            *if (clientIdInForm) arrayOf("client_id" to clientId) else emptyArray()
        )

        val token = try {
            sendPostRequest(client, metadata.tokenEndpoint, authHeader, form, errorPrefix = "Token request failed")
        } catch (e: Exception) {
            logger.error("TLS trust: token request to ${metadata.tokenEndpoint} failed", e)
            throw e
        }
        val expiresAt = if (token.expiresIn > 0) System.currentTimeMillis() + token.expiresIn * 1000 else null
        return SSOToken(accessToken = token.accessToken, idToken = "", accountLabel = accountLabel, expiresAt = expiresAt)
    }

    override suspend fun login(parameters: Parameters): SSOToken {
        val username = parameters["username"] ?: error("Missing 'username'")
        val password = parameters["password"] ?: error("Missing 'password'")

        metadata = discoverOAuthMetadata()
        codeVerifier = CodeVerifier()
        state = State()

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

        val response = sendWithRetryOn401(noRedirectClient, authorizeUri, basicAuth)

        val location = response.headers().firstValue("Location")
            .orElseThrow { error("Missing redirect Location header") }
        val params = parseRedirectQuery(location)

        val code = params["code"] ?: error("Authorization code not found in redirect")

        val token = exchangeCodeForToken(code, noRedirectClient, "openshift-challenging-client", redirectUri, clientIdInForm = false)

        return SSOToken(
            accessToken = token.accessToken,
            idToken = token.idToken,
            accountLabel = username,
            expiresAt = token.expiresAt
        )
     }

    private suspend fun sendWithRetryOn401(
        client: HttpClient,
        authorizeUri: URI,
        basicAuth: String
    ): HttpResponse<Void> {
        var request = HttpRequest.newBuilder()
            .uri(authorizeUri)
            .header("X-Csrf-Token", "1")
            .GET()
            .build()

        var response = client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await()

        if (response.statusCode() == 401) {
            request = HttpRequest.newBuilder()
                .uri(authorizeUri)
                .header("Authorization", basicAuth)
                .header("X-Csrf-Token", "1")
                .GET()
                .build()

            response = client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await()
        }

        if (response.statusCode() !in listOf(302, 303)) {
            error("Authorization failed: ${response.statusCode()}")
        }

        return response
    }
}
