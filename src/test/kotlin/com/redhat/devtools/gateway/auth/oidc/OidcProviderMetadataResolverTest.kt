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

import com.redhat.devtools.gateway.auth.session.SsoLoginException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException

class OidcProviderMetadataResolverTest {

    @Test
    fun `ssoProviderHost extracts host from auth URL`() {
        assertThat(ssoProviderHost("https://sso.redhat.com/auth/realms/redhat-external/"))
            .isEqualTo("sso.redhat.com")
        assertThat(ssoProviderHost("https://custom.example.com:8443/auth/"))
            .isEqualTo("custom.example.com")
    }

    @Test
    fun `ssoProviderHost falls back to raw URL when host missing`() {
        assertThat(ssoProviderHost("not-a-url")).isEqualTo("not-a-url")
    }

    @Test
    fun `isSsoUnreachable is true for DNS connection and timeout failures`() {
        assertThat(isSsoUnreachable(UnknownHostException("sso.redhat.com"))).isTrue()
        assertThat(isSsoUnreachable(ConnectException("Connection refused"))).isTrue()
        assertThat(isSsoUnreachable(NoRouteToHostException("No route to host"))).isTrue()
        assertThat(isSsoUnreachable(SocketTimeoutException("Read timed out"))).isTrue()
        assertThat(isSsoUnreachable(HttpTimeoutException("request timed out"))).isTrue()
        assertThat(isSsoUnreachable(TimeoutException("timed out"))).isTrue()
    }

    @Test
    fun `isSsoUnreachable walks nested causes`() {
        val nested = IOException("send failed", UnknownHostException("sso.redhat.com"))
        assertThat(isSsoUnreachable(nested)).isTrue()
    }

    @Test
    fun `isSsoUnreachable walks nested causes for NoRouteToHostException`() {
        val nested = IOException("send failed", NoRouteToHostException("No route to host"))
        assertThat(isSsoUnreachable(nested)).isTrue()
    }

    @Test
    fun `isSsoUnreachable is false for unrelated failures`() {
        assertThat(isSsoUnreachable(IllegalStateException("bad metadata"))).isFalse()
        assertThat(isSsoUnreachable(IOException("404 Not Found"))).isFalse()
    }

    @Test
    fun `resolve maps unreachable host to SsoLoginException with Token hint`() {
        val resolver = OidcProviderMetadataResolver(
            authUrl = "http://localhost:19999/auth/realms/test/"
        )

        val error = assertThrows<SsoLoginException.Failed> {
            runBlocking { resolver.resolve() }
        }

        assertThat(error.message).contains("Cannot reach SSO provider (localhost)")
        assertThat(error.message).contains("Token authentication")
        assertThat(error.cause).isNotNull
        assertThat(isSsoUnreachable(error.cause!!)).isTrue()
    }
}
