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
package com.redhat.devtools.gateway.auth.code

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import javax.net.ssl.SSLContext

class OAuthDiscoveryTest {

    private val httpClient = mockk<HttpClient>()
    private val discovery = OAuthDiscovery(
        apiServerUrl = "https://api.cluster.example.invalid:6443",
        sslContext = mockk(relaxed = true),
        client = httpClient
    )

    private val metadataJson = """
        {
            "issuer": "https://api.cluster.example.invalid:6443",
            "authorization_endpoint": "https://oauth-openshift.cluster.example.invalid:443/oauth/authorize",
            "token_endpoint": "https://oauth-openshift.cluster.example.invalid:443/oauth/token"
        }
    """.trimIndent()

    private fun mockHttpResponse(statusCode: Int, body: String): HttpResponse<String> {
        val response = mockk<HttpResponse<String>>()
        every { response.statusCode() } returns statusCode
        every { response.body() } returns body
        return response
    }

    private fun stubSendAsync(response: HttpResponse<String>) {
        every {
            httpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
        } returns CompletableFuture.completedFuture(response)
    }

    @Test
    fun `discoverOAuthMetadata returns metadata when response is valid`() = runTest {
        stubSendAsync(mockHttpResponse(200, metadataJson))

        val metadata = discovery.discoverOAuthMetadata().getOrThrow()

        assertThat(metadata.issuer).isEqualTo("https://api.cluster.example.invalid:6443")
        assertThat(metadata.authorizationEndpoint).isEqualTo("https://oauth-openshift.cluster.example.invalid:443/oauth/authorize")
        assertThat(metadata.tokenEndpoint).isEqualTo("https://oauth-openshift.cluster.example.invalid:443/oauth/token")
    }

    @Test
    fun `discoverOAuthMetadata throws on HTTP error`() = runTest {
        stubSendAsync(mockHttpResponse(404, "Not Found"))

        val result = discovery.discoverOAuthMetadata()
        assertThat(result.isFailure).isTrue
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("404")
            .hasMessageContaining("Not Found")
    }

    @Test
    fun `endpointBaseUrls returns distinct base URLs when endpoints differ`() = runTest {
        stubSendAsync(mockHttpResponse(200, metadataJson))

        val urls = discovery.endpointBaseUrls()

        assertThat(urls).containsExactly("https://oauth-openshift.cluster.example.invalid:443")
    }

    @Test
    fun `endpointBaseUrls deduplicates when token and authorize endpoints share the same base`() = runTest {
        val sameHostJson = """
            {
                "issuer": "https://api.cluster.example.invalid:6443",
                "authorization_endpoint": "https://oauth.cluster.example.invalid:443/oauth/authorize",
                "token_endpoint": "https://oauth.cluster.example.invalid:443/oauth/token"
            }
        """.trimIndent()
        stubSendAsync(mockHttpResponse(200, sameHostJson))

        val urls = discovery.endpointBaseUrls()

        assertThat(urls).containsExactly("https://oauth.cluster.example.invalid:443")
    }


}
