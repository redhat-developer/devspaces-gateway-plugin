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

import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Authenticator as JvmAuthenticator

/**
 * OkHttp [okhttp3.Authenticator] that reads proxy/server credentials from the JVM
 * [JvmAuthenticator] (installed by JetBrains [com.intellij.util.net.JdkProxyProvider])
 * without requiring the proxy [InetSocketAddress] to be already resolved.
 *
 * Upstream [okhttp3.Authenticator.JAVA_NET_AUTHENTICATOR] NPEs when
 * `(proxy.address() as InetSocketAddress).address` is null — common for IDE ProxySelectors.
 */
internal class IdeProxyAuthenticator(
    private val defaultDns: Dns = Dns.SYSTEM,
) : okhttp3.Authenticator {

    @Throws(IOException::class)
    override fun authenticate(route: Route?, response: Response): Request? {
        val challenges = response.challenges()

        for (challenge in challenges) {
            if (!"Basic".equals(challenge.scheme, ignoreCase = true)) {
                continue
            }

            val request = response.request
            val url = request.url
            val proxyAuthorization = response.code == 407
            val proxy = route?.proxy ?: Proxy.NO_PROXY
            val dns = route?.address?.dns ?: defaultDns
            val auth = resolveAuth(proxy, proxyAuthorization, url, dns, challenge) ?: continue

            return buildRetryRequest(request, challenge, proxyAuthorization, auth.userName, auth.password)
        }
        return null
    }

    private fun resolveAuth(
        proxy: Proxy,
        proxyAuthorization: Boolean,
        url: HttpUrl,
        dns: Dns,
        challenge: okhttp3.Challenge,
    ) = if (proxyAuthorization) {
        resolveProxyAuth(proxy, url, dns, challenge)
    } else {
        resolveServerAuth(url, proxy, dns, challenge)
    }

    private fun resolveProxyAuth(
        proxy: Proxy,
        url: HttpUrl,
        dns: Dns,
        challenge: okhttp3.Challenge,
    ) = (proxy.address() as? InetSocketAddress)?.let { proxyAddress ->
        JvmAuthenticator.requestPasswordAuthentication(
            proxyAddress.hostString,
            proxy.connectToInetAddress(url, dns),
            proxyAddress.port,
            url.scheme,
            challenge.realm,
            challenge.scheme,
            url.toUrl(),
            JvmAuthenticator.RequestorType.PROXY,
        )
    }

    private fun resolveServerAuth(
        url: HttpUrl,
        proxy: Proxy,
        dns: Dns,
        challenge: okhttp3.Challenge,
    ) = JvmAuthenticator.requestPasswordAuthentication(
        url.host,
        proxy.connectToInetAddress(url, dns),
        url.port,
        url.scheme,
        challenge.realm,
        challenge.scheme,
        url.toUrl(),
        JvmAuthenticator.RequestorType.SERVER,
    )

    private fun buildRetryRequest(
        request: Request,
        challenge: okhttp3.Challenge,
        proxyAuthorization: Boolean,
        userName: String,
        password: CharArray,
    ): Request {
        val credentialHeader = if (proxyAuthorization)
                "Proxy-Authorization"
            else
                "Authorization"
        val credential = Credentials.basic(userName, String(password), challenge.charset)
        return request.newBuilder()
            .header(credentialHeader, credential)
            .build()
    }

    @Throws(IOException::class)
    private fun Proxy.connectToInetAddress(url: HttpUrl, dns: Dns): InetAddress =
        when (type()) {
            Proxy.Type.DIRECT -> dns.lookup(url.host).first()
            else -> {
                val socketAddress = address() as InetSocketAddress
                socketAddress.address
                    ?: dns.lookup(socketAddress.hostName).first()
            }
        }
}
