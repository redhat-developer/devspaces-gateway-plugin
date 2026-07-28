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

import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.auth.code.sendGetRequest
import com.redhat.devtools.gateway.auth.session.SsoLoginException
import com.redhat.devtools.gateway.util.IdeHttpProxy
import java.net.*
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException

class OidcProviderMetadataResolver(
    private val authUrl: String,
    httpClient: HttpClient? = null
) {

    private val discoveryUrl = authUrl.trimEnd('/') + "/.well-known/openid-configuration"

    @Volatile
    private var cached: OIDCProviderMetadata? = null

    private val httpClient: HttpClient by lazy {
        httpClient
            ?: IdeHttpProxy.configure(
                HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NORMAL)
            ).build()
    }

    suspend fun resolve(): OIDCProviderMetadata {
        cached?.let { return it }

        return try {
            val response = httpClient.sendGetRequest(discoveryUrl, "OIDC discovery failed")
            val body = response.body()
            val metadata = OIDCProviderMetadata.parse(body)
            cached = metadata
            metadata
        } catch (e: Exception) {
            when {
                e is SsoLoginException -> throw e
                !isSsoUnreachable(e) -> throw e
                else -> {
                    val host = ssoProviderHost(authUrl)
                    throw SsoLoginException.Failed(ssoUnreachableMessage(host)).apply {
                        initCause(e)
                    }
                }
            }
        }
    }
}

/**
 * Returns `true` if [throwable] (or a cause) indicates the SSO provider host could not be reached
 * (DNS, connection refused, or network/HTTP timeout).
 */
internal fun isSsoUnreachable(throwable: Throwable): Boolean =
    generateSequence(throwable) { it.cause }.any { cause ->
        cause is UnknownHostException ||
            cause is ConnectException ||
            cause is NoRouteToHostException ||
            cause is HttpTimeoutException ||
            cause is TimeoutException ||
            cause is SocketTimeoutException
    }

internal fun ssoProviderHost(authUrl: String): String =
    runCatching { URI(authUrl).host }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: authUrl

internal fun ssoUnreachableMessage(host: String): String =
    DevSpacesBundle.message(
        "connector.wizard_step.openshift_connection.error.sso_unreachable",
        host
    )
