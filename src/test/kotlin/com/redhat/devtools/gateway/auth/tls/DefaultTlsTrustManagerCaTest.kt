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

import com.redhat.devtools.gateway.auth.tls.TlsTrustManagerTestFixtures.API_SERVER_URL
import com.redhat.devtools.gateway.auth.tls.TlsTrustManagerTestFixtures.createManager
import com.redhat.devtools.gateway.auth.tls.TlsTrustManagerTestFixtures.kubeConfigWithInsecureSkip
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import javax.net.ssl.X509TrustManager

class DefaultTlsTrustManagerCaTest {

    @Test
    fun `#mergeTrustedContext fails when no trusted certificates resolved`() {
        runBlocking {
            val manager = createManager()
            val exception = kotlin.runCatching {
                manager.mergeTrustedContext(listOf(API_SERVER_URL), certificateAuthority = null)
            }.exceptionOrNull()

            assertThat(exception)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(API_SERVER_URL)
        }
    }

    @Test
    fun `#createOpenShiftTlsContext honors kubeconfig insecure-skip-tls-verify`() {
        runBlocking {
            val manager = createManager(
                kubeConfigProvider = { listOf(kubeConfigWithInsecureSkip()) },
            )

            val tlsContext = manager.createOpenShiftTlsContext(
                API_SERVER_URL,
                decisionHandler = {
                    error("trust dialog must not be shown when insecure-skip-tls-verify is set")
                },
            )

            assertThat(tlsContext.isInsecure).isTrue()

            val trustManager = tlsContext.trustManager as X509TrustManager
            trustManager.checkServerTrusted(emptyArray(), "RSA")
            assertThat(trustManager.acceptedIssuers).isEmpty()
        }
    }
}
