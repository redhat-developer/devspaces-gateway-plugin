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

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import javax.net.ssl.SSLContext

class OpenShiftAuthCodeFlowTest {

    private val discovery = mockk<OAuthDiscovery>()

    private val authCodeFlow = OpenShiftAuthCodeFlow(
        apiServerUrl = "https://api.cluster.example.invalid:6443",
        redirectUri = URI("http://localhost:12345/callback"),
        sslContext = mockk(relaxed = true),
        discovery = discovery
    )

    private val validMetadata = OAuthMetadata(
        issuer = "https://api.cluster.example.invalid:6443",
        authorizationEndpoint = "https://oauth-openshift.cluster.example.invalid:443/oauth/authorize",
        tokenEndpoint = "https://oauth-openshift.cluster.example.invalid:443/oauth/token"
    )

    @Test
    fun `startAuthFlow returns AuthCodeRequest when discovery succeeds`() = runTest {
        coEvery { discovery.discoverOAuthMetadata() } returns Result.success(validMetadata)

        val request = authCodeFlow.startAuthFlow()

        assertThat(request.authorizationUri).isNotNull
        assertThat(request.authorizationUri.toString())
            .startsWith("https://oauth-openshift.cluster.example.invalid:443/oauth/authorize")
        assertThat(request.codeVerifier).isNotNull
        assertThat(request.nonce).isNotNull
    }

    @Test
    fun `startAuthFlow propagates exception when discovery fails`() = runTest {
        coEvery { discovery.discoverOAuthMetadata() } returns Result.failure(IllegalStateException("Discovery failed"))

        val result = kotlin.runCatching { authCodeFlow.startAuthFlow() }
        assertThat(result.isFailure).isTrue
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Discovery failed")
    }

    @Test
    fun `handleCallback throws when code parameter is missing`() = runTest {
        val result = kotlin.runCatching { authCodeFlow.handleCallback(emptyMap()) }
        assertThat(result.isFailure).isTrue
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Missing 'code' parameter in callback")
    }

    @Test
    fun `handleCallback throws when redirectUri is null`() = runTest {
        val flowWithoutRedirect = OpenShiftAuthCodeFlow(
            apiServerUrl = "https://api.cluster.example.invalid:6443",
            redirectUri = null,
            sslContext = mockk(relaxed = true),
            discovery = discovery
        )

        val result = kotlin.runCatching { flowWithoutRedirect.handleCallback(mapOf("code" to "abc123")) }
        assertThat(result.isFailure).isTrue
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("redirectUri is required for code exchange")
    }

    @Test
    fun `login propagates exception when discovery fails`() = runTest {
        coEvery { discovery.discoverOAuthMetadata() } returns Result.failure(IllegalStateException("Discovery failed"))

        val result = kotlin.runCatching {
            authCodeFlow.login(mapOf("username" to "test", "password" to "pass"))
        }
        assertThat(result.isFailure).isTrue
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Discovery failed")
    }

    @Test
    fun `login throws when username is missing`() = runTest {
        val result = kotlin.runCatching { authCodeFlow.login(mapOf("password" to "pass")) }
        assertThat(result.isFailure).isTrue
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Missing 'username'")
    }

    @Test
    fun `login throws when password is missing`() = runTest {
        val result = kotlin.runCatching { authCodeFlow.login(mapOf("username" to "test")) }
        assertThat(result.isFailure).isTrue
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Missing 'password'")
    }
}
