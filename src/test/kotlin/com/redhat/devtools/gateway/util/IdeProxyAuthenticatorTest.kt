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

import okhttp3.Address
import okhttp3.Authenticator
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.IOException
import javax.net.SocketFactory

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("java.net.Authenticator")
class IdeProxyAuthenticatorTest {

    private val fixedDns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
    private var savedAuthenticator: java.net.Authenticator? = null

    @BeforeEach
    fun installJvmAuthenticator() {
        savedAuthenticator = java.net.Authenticator.getDefault()
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
                PasswordAuthentication("testuser", "testpass".toCharArray())
        })
    }

    @AfterEach
    fun clearJvmAuthenticator() {
        java.net.Authenticator.setDefault(savedAuthenticator)
    }

    @Test
    fun `authenticate with unresolved proxy address returns Proxy-Authorization without NPE`() {
        val unresolved = InetSocketAddress.createUnresolved("proxy.test", 3128)
        val proxy = Proxy(Proxy.Type.HTTP, unresolved)
        val route = Route(httpAddress(), proxy, unresolved)
        val response = proxyAuthRequiredResponse()

        val followUp = IdeProxyAuthenticator(fixedDns).authenticate(route, response)

        assertThat(followUp).isNotNull
        assertThat(followUp!!.header("Proxy-Authorization"))
            .isEqualTo(okhttp3.Credentials.basic("testuser", "testpass"))
    }

    // Regression sentinel — documents upstream NPE in OkHttp 4.12 (square/okhttp#9100).
    // Drop or @Disabled when upgrading OkHttp past the fix.
    @Test
    fun `JavaNetAuthenticator NPEs on unresolved proxy address (documents upstream bug)`() {
        val unresolved = InetSocketAddress.createUnresolved("proxy.test", 3128)
        val proxy = Proxy(Proxy.Type.HTTP, unresolved)
        val route = Route(httpAddress(), proxy, unresolved)
        val response = proxyAuthRequiredResponse()

        assertThatCode {
            Authenticator.JAVA_NET_AUTHENTICATOR.authenticate(route, response)
        }.isInstanceOf(NullPointerException::class.java)
            .hasMessageContaining("address")
    }

    private fun httpAddress(): Address =
        Address(
            "api.example.com",
            443,
            fixedDns,
            SocketFactory.getDefault(),
            null,
            null,
            null,
            Authenticator.NONE,
            null,
            listOf(Protocol.HTTP_1_1),
            emptyList(),
            object : ProxySelector() {
                override fun select(uri: URI) = listOf(Proxy.NO_PROXY)
                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}
            },
        )

    private fun proxyAuthRequiredResponse(): Response {
        val request = Request.Builder().url("https://api.example.com/").build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(407)
            .message("Proxy Authentication Required")
            .header("Proxy-Authenticate", "Basic realm=\"proxy\"")
            .build()
    }
}
