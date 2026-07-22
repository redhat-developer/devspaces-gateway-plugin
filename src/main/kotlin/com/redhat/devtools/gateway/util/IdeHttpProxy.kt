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
package com.redhat.devtools.gateway.util

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.net.JdkProxyProvider
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.io.IOException

/**
 * Applies JetBrains IDEA/Gateway HTTP proxy settings (static, PAC, no-proxy, auth)
 * to plugin HTTP clients via [JdkProxyProvider] / the JVM [ProxySelector].
 */
object IdeHttpProxy {

    private val noProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}
    }

    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        configure(builder, ideProxySelector())

    fun configure(builder: OkHttpClient.Builder, selector: ProxySelector): OkHttpClient.Builder =
        builder
            .proxySelector(selector)
            .proxyAuthenticator(Authenticator.JAVA_NET_AUTHENTICATOR)

    /**
     * Configures a [HttpClient.Builder] to use an IDE-compatible proxy selector.
     *
     * When [proxySelector] is omitted, the IDE proxy selector from [JdkProxyProvider] is used.
     * Proxy authentication is handled by the JVM-wide [Authenticator] set by
     * [JdkProxyProvider.ensureDefault] — no per-client auth setup needed.
     *
     * When [proxySelector] is provided, it overrides the default IDE selector, enabling
     * test scenarios that require a custom proxy configuration.
     *
     * @param builder the HTTP client builder to configure
     * @param proxySelector optional proxy selector; defaults to the IDE proxy selector from [ideProxySelector]
     */
    fun configure(builder: HttpClient.Builder, proxySelector: ProxySelector = ideProxySelector()): HttpClient.Builder =
        builder.proxy(proxySelector)

    private fun ideProxySelector(): ProxySelector =
        runCatching {
            JdkProxyProvider.ensureDefault()
            JdkProxyProvider.getInstance().proxySelector
        }.getOrElse {
            thisLogger().warn("Failed to obtain IDE proxy selector, falling back to JVM default", it)
            ProxySelector.getDefault() ?: noProxySelector
        }
}
