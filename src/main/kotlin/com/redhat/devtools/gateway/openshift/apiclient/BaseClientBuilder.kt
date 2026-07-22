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
package com.redhat.devtools.gateway.openshift.apiclient

import com.redhat.devtools.gateway.util.IdeHttpProxy
import io.kubernetes.client.openapi.ApiClient
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Authenticator
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private const val DEFAULT_HTTP_TIMEOUT_SECONDS = 30L

/**
 * Interface for building OpenShift API clients.
 */
interface OpenShiftClientBuilder {
    fun build(): ApiClient
    fun readTimeout(timeout: Long, unit: TimeUnit): OpenShiftClientBuilder
}

/**
 * Base class for building OpenShift API clients.
 * Provides shared read timeout, HTTP client creation, and URL normalization.
 */
abstract class BaseClientBuilder : OpenShiftClientBuilder {
    private var readTimeoutSeconds: Long = 0

    override fun readTimeout(timeout: Long, unit: TimeUnit): OpenShiftClientBuilder {
        this.readTimeoutSeconds = unit.toSeconds(timeout)
        return this
    }

    protected fun applyReadTimeout(client: ApiClient): ApiClient {
        if (readTimeoutSeconds > 0) {
            client.httpClient = client.httpClient.newBuilder()
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .build()
        }
        return client
    }

    protected fun normalizeBasePath(server: String): String =
        server.trim().removeSuffix("/")

    /**
     * Creates an [OkHttpClient] with the given [sslContext] and [trustManager].
     * Uses HTTP/1.1 (not HTTP/2): some OpenShift clusters hang on HTTP/2.
     */
    protected fun createHttpClient(sslContext: SSLContext, trustManager: X509TrustManager): OkHttpClient =
        IdeHttpProxy.configure(
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .connectTimeout(DEFAULT_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(DEFAULT_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(DEFAULT_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .protocols(listOf(Protocol.HTTP_1_1))
        ).build()
}
