/*
 * Copyright (c) 2024-2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.openshift

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.thisLogger
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.auth.ApiKeyAuth
import io.kubernetes.client.openapi.auth.HttpBasicAuth
import io.kubernetes.client.openapi.auth.HttpBearerAuth
import io.kubernetes.client.openapi.models.V1Status
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Helpers for building [ApiClient] instances used by OpenShift / Kubernetes integration.
 */
object ApiClientUtils {

    /**
     * Clone [base] for pod exec: fork TLS from [base]'s OkHttpClient, new dispatcher/pool/ping, then
     * isolate exec traffic on a fresh pool/dispatcher. Do not call [ApiClient.setSslCaCert] here—the
     * CA stream is single-use ([ApiClient.applySslSettings] consumes it). Internal for tests.
     */
    internal fun cloneForExec(base: ApiClient): ApiClient {
        val newHttp = cloneHttpClient(base.httpClient, zeroPingForExec = true)
        return ApiClient(newHttp).apply {
            basePath = base.basePath
            setDebugging(base.isDebugging)
            /*
             * Do not call:
             * - setVerifyingSsl
             * - setSslCaCert
             * - setKeyManagers
             * Each triggers ApiClient.applySslSettings(), which reads sslCaCert to EOF.
             * The kube client keeps a single shared InputStream. A second read yields no certs and throws
             * IllegalArgumentException("expected non-empty set of trusted certificates").
             * newHttp is forked from base.httpClient and already carries the correct TLS stack.
             */
            setReadTimeout(base.readTimeout)
            setConnectTimeout(base.connectTimeout)
            setWriteTimeout(base.writeTimeout)

            copyAuthentications(base, this)

            readDefaultHeaderMap(base).forEach { (k, v) -> addDefaultHeader(k, v) }
            readDefaultCookieMap(base).forEach { (k, v) -> addDefaultCookie(k, v) }
        }
    }

    private fun copyAuthentications(source: ApiClient, target: ApiClient) {
        source.authentications.forEach { (name, auth) ->
            val dest = target.getAuthentication(name) ?: return@forEach
            when (auth) {
                is ApiKeyAuth -> {
                    if (dest is ApiKeyAuth) {
                        dest.apiKey = auth.apiKey
                        dest.apiKeyPrefix = auth.apiKeyPrefix
                    }
                }

                is HttpBasicAuth -> {
                    if (dest is HttpBasicAuth) {
                        dest.username = auth.username
                        dest.password = auth.password
                    }
                }

                is HttpBearerAuth -> {
                    if (dest is HttpBearerAuth) {
                        // No access to private "scheme" – always use correct "Bearer"
                        dest.bearerToken = auth.bearerToken
                    }
                }
            }
        }
    }

    private fun readDefaultHeaderMap(client: ApiClient): Map<String, String> =
        readPrivateMap(client, "defaultHeaderMap")

    private fun readDefaultCookieMap(client: ApiClient): Map<String, String> =
        readPrivateMap(client, "defaultCookieMap")

    private fun readPrivateMap(client: ApiClient, fieldName: String): Map<String, String> =
        try {
            val field = ApiClient::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(client) as? Map<String, String>) ?: emptyMap()
        } catch (e: Exception) {
            thisLogger<ApiClientUtils>().warn(
                "Could not read ApiClient.$fieldName via reflection; cloned exec client will omit these entries.",
                e
            )
            emptyMap()
        }

    /**
     * Forks [source] with a fresh [okhttp3.ConnectionPool] and [okhttp3.Dispatcher] (same max request
     * limits as the original dispatcher). When [zeroPingForExec] is true, WebSocket ping is disabled
     * for Kubernetes exec; otherwise ping settings are left as inherited from [source].
     */
    private fun cloneHttpClient(source: OkHttpClient, zeroPingForExec: Boolean): OkHttpClient {
        val originalDispatcher = source.dispatcher
        val newDispatcher = okhttp3.Dispatcher().apply {
            maxRequests = originalDispatcher.maxRequests
            maxRequestsPerHost = originalDispatcher.maxRequestsPerHost
        }
        val builder = source.newBuilder()
            .dispatcher(newDispatcher)
            .connectionPool(okhttp3.ConnectionPool())
        if (zeroPingForExec) {
            builder.pingInterval(0, TimeUnit.SECONDS) // IMPORTANT for Exec
        }
        return builder.build()
    }

}

fun ApiException.shouldBeIgnored(): Boolean =
    code == 403 || code == 404
fun ApiException.isRetryable(): Boolean =
    code in setOf(429, 500, 502, 503, 504)

