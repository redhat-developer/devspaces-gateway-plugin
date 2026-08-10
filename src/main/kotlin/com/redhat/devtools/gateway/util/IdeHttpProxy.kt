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

private const val KEY_DISABLED_AUTH_TUNNELING_SCHEMES = "jdk.http.auth.tunneling.disabledSchemes"
private const val KEY_DISABLED_AUTH_PROXYING_SCHEMES = "jdk.http.auth.proxying.disabledSchemes"

/**
 * Applies JetBrains IDEA/Gateway HTTP proxy settings (static, PAC, no-proxy, auth)
 * to plugin HTTP clients via [JdkProxyProvider] / the JVM [ProxySelector].
 */
object IdeHttpProxy {

    /** Shared OkHttp proxy authenticator; safe with unresolved IDE proxy addresses. */
    val PROXY_AUTHENTICATOR: Authenticator = IdeProxyAuthenticator()

    private val noProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}
    }

    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        configure(builder, proxySelector())

    fun configure(builder: OkHttpClient.Builder, selector: ProxySelector): OkHttpClient.Builder =
        builder
            .proxySelector(selector)
            .proxyAuthenticator(PROXY_AUTHENTICATOR)

    /**
     * Configures a [HttpClient.Builder] to use an IDE-compatible proxy selector and authenticator.
     *
     * Java's [HttpClient] does **not** use [java.net.Authenticator.getDefault] unless set on the builder.
     * This method attaches the JVM [java.net.Authenticator.getDefault] (installed by
     * [JdkProxyProvider.ensureDefault]) to the builder.
     *
     * Also allows Basic for HTTPS CONNECT / HTTP proxying when the corresponding
     * `jdk.http.auth.*.disabledSchemes` properties are **unset** (see
     * [enableBasicAuthForHttpProxyTunnelsIfUnset]). Explicit user/IDE values are preserved.
     * OkHttp does not need those JDK properties ([PROXY_AUTHENTICATOR] handles 407).
     *
     * When [proxySelector] is provided, it overrides the default IDE selector, enabling
     * test scenarios that require a custom proxy configuration.
     *
     * @param builder the HTTP client builder to configure
     * @param proxySelector optional proxy selector; defaults to the IDE proxy selector from [proxySelector]
     */
    fun configure(
        builder: HttpClient.Builder,
        proxySelector: ProxySelector = this.proxySelector(),
    ): HttpClient.Builder {
        runCatching { JdkProxyProvider.ensureDefault() }
            .onFailure { thisLogger().warn("Failed to ensure default JDK proxy provider", it) }
        enableBasicAuthForHttpProxyTunnelsIfUnset()
        val withProxy = builder.proxy(proxySelector)
        val authenticator = java.net.Authenticator.getDefault() ?: return withProxy
        return withProxy.authenticator(authenticator)
    }

    /**
     * Allows Basic for HTTPS CONNECT / HTTP proxying when configuring [HttpClient].
     *
     * These JDK properties are comma-separated lists of **scheme names to disable**
     * (e.g. `Basic`), not booleans. When unset, the JDK defaults to disabling Basic for
     * HTTPS CONNECT (since 8u111). Setting a property to `""` means an empty disable-list,
     * i.e. **no schemes are disabled** — so Basic (and others) are effectively enabled.
     *
     * Sets each property to `""` only when that property is **unset** (`null`). Non-empty
     * values set by the user/IDE are left unchanged. Already-empty values are left as-is.
     */
    private fun enableBasicAuthForHttpProxyTunnelsIfUnset() {
        var changed = false
        // all schemes allowed
        if (System.getProperty(KEY_DISABLED_AUTH_TUNNELING_SCHEMES) == null) {
            System.setProperty(KEY_DISABLED_AUTH_TUNNELING_SCHEMES, "")
            changed = true
        }
        if (System.getProperty(KEY_DISABLED_AUTH_PROXYING_SCHEMES) == null) {
            System.setProperty(KEY_DISABLED_AUTH_PROXYING_SCHEMES, "")
            changed = true
        }
        if (changed) {
            thisLogger().debug("Enabled Basic auth for HTTP proxy tunnels (unset jdk.http.auth.*.disabledSchemes)")
        }
    }

    /**
     * Returns the IDE-compatible [ProxySelector] from [JdkProxyProvider],
     * falling back to the JVM default selector or a no-proxy selector when
     * unavailable.
     */
    fun proxySelector(): ProxySelector =
        runCatching {
            JdkProxyProvider.ensureDefault()
            JdkProxyProvider.getInstance().proxySelector
        }.getOrElse {
            thisLogger().warn("Failed to obtain IDE proxy selector, falling back to JVM default", it)
            ProxySelector.getDefault() ?: noProxySelector
        }
}
