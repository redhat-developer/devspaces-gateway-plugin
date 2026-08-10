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
package com.redhat.devtools.gateway.auth.tls

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import javax.net.ssl.SSLException

class TlsConnectionProbeDirectTest {

    private val ctx = TlsConnectionProbeTestContext()

    @BeforeEach
    fun setUp() {
        ctx.captureAuthenticator()
    }

    @AfterEach
    fun tearDown() {
        ctx.close()
    }

    @Test
    fun `#connect succeeds over DIRECT`() {
        val tlsServer = TlsConnectionProbeTestFixtures.startTlsServer(ctx)
        val uri = URI("https://127.0.0.1:${tlsServer.localPort}")

        assertThatCode {
            TlsConnectionProbe.connect(
                uri,
                SslContextFactory.insecure().sslContext,
                TlsConnectionProbeTestFixtures.directSelector()
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `#connect succeeds with matching hostname`() {
        val tlsServer = TlsConnectionProbeTestFixtures.startTlsServer(ctx)
        val uri = URI("https://localhost:${tlsServer.localPort}")

        assertThatCode {
            TlsConnectionProbe.connect(
                uri,
                SslContextFactory.insecure().sslContext,
                TlsConnectionProbeTestFixtures.directSelector()
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `#connect fails with hostname mismatch`() {
        val tlsServer = TlsConnectionProbeTestFixtures.startMismatchCertServer(ctx)
        val uri = URI("https://127.0.0.1:${tlsServer.localPort}")

        val error = runCatching {
            TlsConnectionProbe.connect(
                uri,
                SslContextFactory.insecure().sslContext,
                TlsConnectionProbeTestFixtures.directSelector()
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(SSLException::class.java)
    }
}
