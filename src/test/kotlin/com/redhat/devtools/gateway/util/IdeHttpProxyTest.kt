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

import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.io.IOException

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("java.net.Authenticator")
@ResourceLock("jdk.http.auth.system.properties")
class IdeHttpProxyTest {

    private val fakeSelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> =
            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("proxy.test", 8080)))

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}
    }

    private var previousAuthenticator: Authenticator? = null
    private var previousTunneling: String? = null
    private var previousProxying: String? = null

    @BeforeEach
    fun saveAndInstallJvmAuthenticator() {
        previousAuthenticator = Authenticator.getDefault()
        previousTunneling = System.getProperty("jdk.http.auth.tunneling.disabledSchemes")
        previousProxying = System.getProperty("jdk.http.auth.proxying.disabledSchemes")
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
                PasswordAuthentication("testuser", "testpass".toCharArray())
        })
    }

    @AfterEach
    fun restoreJvmState() {
        Authenticator.setDefault(previousAuthenticator)
        setOrClearProperty("jdk.http.auth.tunneling.disabledSchemes", previousTunneling)
        setOrClearProperty("jdk.http.auth.proxying.disabledSchemes", previousProxying)
    }

    @Test
    fun `configure OkHttpClient uses injected selector and IDE proxy authenticator`() {
        val client = IdeHttpProxy.configure(OkHttpClient.Builder(), fakeSelector).build()

        assertThat(client.proxySelector).isSameAs(fakeSelector)
        assertThat(client.proxyAuthenticator).isSameAs(IdeHttpProxy.PROXY_AUTHENTICATOR)
    }

    @Test
    fun `configure HttpClient enables unset disabledSchemes and attaches authenticator`() {
        System.clearProperty("jdk.http.auth.tunneling.disabledSchemes")
        System.clearProperty("jdk.http.auth.proxying.disabledSchemes")

        val client = IdeHttpProxy.configure(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1),
            fakeSelector
        ).build()

        assertThat(client.proxy().orElse(null)).isSameAs(fakeSelector)
        assertThat(client.authenticator().orElse(null)).isSameAs(Authenticator.getDefault())
        assertThat(System.getProperty("jdk.http.auth.tunneling.disabledSchemes")).isEmpty()
        assertThat(System.getProperty("jdk.http.auth.proxying.disabledSchemes")).isEmpty()
    }

    @Test
    fun `configure HttpClient preserves user-set disabledSchemes`() {
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "Basic")
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "Basic,Digest")

        IdeHttpProxy.configure(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1),
            fakeSelector
        )

        assertThat(System.getProperty("jdk.http.auth.tunneling.disabledSchemes")).isEqualTo("Basic")
        assertThat(System.getProperty("jdk.http.auth.proxying.disabledSchemes")).isEqualTo("Basic,Digest")
    }

    @Test
    fun `configure HttpClient enables only unset disabledSchemes property`() {
        System.clearProperty("jdk.http.auth.tunneling.disabledSchemes")
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "Basic")

        IdeHttpProxy.configure(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1),
            fakeSelector
        )

        assertThat(System.getProperty("jdk.http.auth.tunneling.disabledSchemes")).isEmpty()
        assertThat(System.getProperty("jdk.http.auth.proxying.disabledSchemes")).isEqualTo("Basic")
    }

    private fun setOrClearProperty(key: String, value: String?) {
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }
}
