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

import okhttp3.Authenticator
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.io.IOException

class IdeHttpProxyTest {

    private val fakeSelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> =
            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("proxy.test", 8080)))

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}
    }

    @Test
    fun `configure OkHttpClient uses injected selector and Java net authenticator`() {
        val client = IdeHttpProxy.configure(OkHttpClient.Builder(), fakeSelector).build()

        assertThat(client.proxySelector).isSameAs(fakeSelector)
        assertThat(client.proxyAuthenticator).isSameAs(Authenticator.JAVA_NET_AUTHENTICATOR)
    }

    @Test
    fun `configure HttpClient builds successfully with injected selector`() {
        IdeHttpProxy.configure(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1),
            fakeSelector
        ).build()
    }
}
