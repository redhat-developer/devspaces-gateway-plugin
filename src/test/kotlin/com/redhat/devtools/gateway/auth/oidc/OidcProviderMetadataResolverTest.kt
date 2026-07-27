/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.auth.oidc

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.redhat.devtools.gateway.auth.session.SsoLoginException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

class OidcProviderMetadataResolverTest {

    private val validMetadataJson = """
        {
            "issuer": "https://sso.example.com/realms/test",
            "authorization_endpoint": "https://sso.example.com/realms/test/authorize",
            "token_endpoint": "https://sso.example.com/realms/test/token",
            "jwks_uri": "https://sso.example.com/realms/test/certs",
            "response_types_supported": ["code"],
            "subject_types_supported": ["public"],
            "id_token_signing_alg": "RS256"
        }
    """.trimIndent()

    private fun mockHttpResponse(statusCode: Int, body: String): HttpResponse<String> {
        val response = mockk<HttpResponse<String>>()
        every { response.statusCode() } returns statusCode
        every { response.body() } returns body
        return response
    }

    private fun stubSendAsync(httpClient: HttpClient, response: HttpResponse<String>) {
        every {
            httpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
        } returns CompletableFuture.completedFuture(response)
    }

    @Test
    fun `ssoProviderHost extracts host from auth URL`() {
        assertThat(ssoProviderHost("https://sso.redhat.com/auth/realms/redhat-external/"))
            .isEqualTo("sso.redhat.com")
        assertThat(ssoProviderHost("https://custom.example.com:8443/auth/"))
            .isEqualTo("custom.example.com")
    }

    @Test
    fun `ssoProviderHost falls back to raw URL when host missing`() {
        assertThat(ssoProviderHost("not-a-url")).isEqualTo("not-a-url")
    }

    @Test
    fun `isSsoUnreachable is true for DNS connection and timeout failures`() {
        assertThat(isSsoUnreachable(UnknownHostException("sso.redhat.com"))).isTrue()
        assertThat(isSsoUnreachable(ConnectException("Connection refused"))).isTrue()
        assertThat(isSsoUnreachable(NoRouteToHostException("No route to host"))).isTrue()
        assertThat(isSsoUnreachable(SocketTimeoutException("Read timed out"))).isTrue()
        assertThat(isSsoUnreachable(HttpTimeoutException("request timed out"))).isTrue()
        assertThat(isSsoUnreachable(TimeoutException("timed out"))).isTrue()
    }

    @Test
    fun `isSsoUnreachable walks nested causes`() {
        val nested = IOException("send failed", UnknownHostException("sso.redhat.com"))
        assertThat(isSsoUnreachable(nested)).isTrue()
    }

    @Test
    fun `isSsoUnreachable walks nested causes for NoRouteToHostException`() {
        val nested = IOException("send failed", NoRouteToHostException("No route to host"))
        assertThat(isSsoUnreachable(nested)).isTrue()
    }

    @Test
    fun `isSsoUnreachable is false for unrelated failures`() {
        assertThat(isSsoUnreachable(IllegalStateException("bad metadata"))).isFalse()
        assertThat(isSsoUnreachable(IOException("404 Not Found"))).isFalse()
    }

    @Test
    fun `resolve maps unreachable host to SsoLoginException with Token hint`() {
        val resolver = OidcProviderMetadataResolver(
            authUrl = "http://localhost:19999/auth/realms/test/"
        )

        val error = assertThrows<SsoLoginException.Failed> {
            runBlocking { resolver.resolve() }
        }

        assertThat(error.message).contains("Cannot reach SSO provider (localhost)")
        assertThat(error.message).contains("Token authentication")
        assertThat(error.cause).isNotNull
        assertThat(isSsoUnreachable(error.cause!!)).isTrue()
    }

    @Test
    fun `resolve returns OIDC metadata on successful response`() = runTest {
        val httpClient = mockk<HttpClient>()
        val resolver = OidcProviderMetadataResolver(
            authUrl = "https://sso.example.com/auth/realms/test/",
            httpClient = httpClient
        )
        stubSendAsync(httpClient, mockHttpResponse(200, validMetadataJson))

        val metadata = resolver.resolve()

        assertThat(metadata.issuer.toString()).isEqualTo("https://sso.example.com/realms/test")
    }

    @Test
    fun `resolve caches metadata and skips subsequent HTTP requests`() = runTest {
        val httpClient = mockk<HttpClient>()
        every {
            httpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
        } answers
            { CompletableFuture.completedFuture(mockHttpResponse(200, validMetadataJson)) }
        val resolver = OidcProviderMetadataResolver(
            authUrl = "https://sso.example.com/auth/realms/test/",
            httpClient = httpClient
        )

        resolver.resolve()
        resolver.resolve()

        verify(exactly = 1) {
            httpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
        }
    }

    @Test
    fun `resolve throws on non-2xx response`() = runTest {
        val httpClient = mockk<HttpClient>()
        val resolver = OidcProviderMetadataResolver(
            authUrl = "https://sso.example.com/auth/realms/test/",
            httpClient = httpClient
        )
        stubSendAsync(httpClient, mockHttpResponse(503, "Service Unavailable"))

        val error = assertThrows<IllegalStateException> {
            resolver.resolve()
        }

        assertThat(error.message).contains("503")
        assertThat(error.message).contains("Service Unavailable")
    }

    @Test
    fun `resolve uses proxy-configured HTTP client when provided`() = runTest {
        val httpClient = mockk<HttpClient>()
        stubSendAsync(httpClient, mockHttpResponse(200, validMetadataJson))
        val resolver = OidcProviderMetadataResolver(
            authUrl = "https://sso.example.com/auth/realms/test/",
            httpClient = httpClient
        )

        // The resolver accepts the injected httpClient instead of building
        // a default client internally, allowing tests to control HTTP behavior.
        val metadata = resolver.resolve()
        assertThat(metadata.issuer.toString()).isEqualTo("https://sso.example.com/realms/test")
    }
}
