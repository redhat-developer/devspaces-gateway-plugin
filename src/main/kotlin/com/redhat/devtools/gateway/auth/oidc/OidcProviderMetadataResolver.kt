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
package com.redhat.devtools.gateway.auth.oidc

import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.openid.connect.sdk.op.OIDCProviderConfigurationRequest
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.auth.session.SsoLoginException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException

class OidcProviderMetadataResolver(
    private val authUrl: String
) {
    private val issuer = Issuer(authUrl)

    @Volatile
    private var cached: OIDCProviderMetadata? = null

    suspend fun resolve(): OIDCProviderMetadata {
        cached?.let { return it }

        return try {
            val request = OIDCProviderConfigurationRequest(issuer)
            val httpResponse = withContext(Dispatchers.IO) {
                request.toHTTPRequest().send()
            }
            val metadata = OIDCProviderMetadata.parse(httpResponse.bodyAsJSONObject)
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
