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
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.future.await
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
    private val sslContext: SSLContext,
    private val discovery: OAuthDiscovery = OAuthDiscovery(apiServerUrl, sslContext),
) : AuthCodeFlow {

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

    override suspend fun startAuthFlow(): AuthCodeRequest {
        metadata = discovery.discoverOAuthMetadata()
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

    override suspend fun handleCallback(parameters: Parameters): SSOToken {
        val code: String = parameters["code"]
            ?: error("Missing 'code' parameter in callback")
        val uri = redirectUri
            ?: error("redirectUri is required for code exchange")
        return exchangeCodeForToken(
            code,
            discoveryClient,
            "openshift-cli-client",
            uri,
            accountLabel = "openshift-user"
        )
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

        val token = requestToken(client, authHeader, form)
        val expiresAt = if (token.expiresIn > 0) System.currentTimeMillis() + token.expiresIn * 1000 else null
        return SSOToken(accessToken = token.accessToken, idToken = "", accountLabel = accountLabel, expiresAt = expiresAt)
    }

    private suspend fun requestToken(
        client: HttpClient,
        authHeader: String,
        form: String
    ): AccessTokenResponseJson {
        val token = try {
            client.sendPostRequest(metadata.tokenEndpoint, authHeader, form, errorPrefix = "Token request failed")
        } catch (e: Exception) {
            thisLogger().error("TLS trust: token request to ${metadata.tokenEndpoint} failed", e)
            throw e
        }
        return token
    }

    override suspend fun login(parameters: Parameters): SSOToken {
        val username = parameters["username"] ?: error("Missing 'username'")
        val password = parameters["password"] ?: error("Missing 'password'")

        metadata = discovery.discoverOAuthMetadata()
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

        var response = client
            .sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .await()

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
