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

import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.util.toServerBaseUrl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.net.http.HttpClient
import javax.net.ssl.SSLContext

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class OAuthMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint")
    val authorizationEndpoint: String,
    @SerialName("token_endpoint")
    val tokenEndpoint: String
)

class OAuthDiscovery(
    apiServerUrl: String,
    sslContext: SSLContext,
    private val client: HttpClient = HttpClient.newBuilder()
        .sslContext(sslContext)
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
) {

    private val discoveryUrl = "$apiServerUrl/.well-known/oauth-authorization-server"

    suspend fun discoverOAuthMetadata(): Result<OAuthMetadata> = runCatching {
        val response = client.sendGetRequest(discoveryUrl)
        json.decodeFromString(OAuthMetadata.serializer(), response.body())
    }.onFailure { e ->
        if (e is CancellationException) throw e
        thisLogger().warn("TLS trust: OAuth discovery request to $discoveryUrl failed", e)
    }

    suspend fun endpointBaseUrls(): List<String> {
        thisLogger().info("TLS trust: discovering OAuth endpoints from $discoveryUrl")
        val md = discoverOAuthMetadata().getOrThrow()
        val urls = listOf(md.tokenEndpoint, md.authorizationEndpoint)
            .map { URI(it).toServerBaseUrl() }
            .distinct()
        thisLogger().info(
            "TLS trust: OAuth discovery succeeded (issuer=${md.issuer}, " +
                "endpoints=${urls.joinToString()})"
        )
        return urls
    }
}
