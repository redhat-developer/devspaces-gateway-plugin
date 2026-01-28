/*
 * Copyright (c) 2025-2026 Red Hat, Inc.
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

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object SslContextFactory {

    fun empty(): TlsContext =
        fromTrustedCerts(emptyList())

    fun insecure(): TlsContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }

        return TlsContext(sslContext, trustAll)
    }

    fun fromTrustedCerts(certs: List<X509Certificate>): TlsContext {
        val keyStore = KeyStoreUtils.createEmpty()

        certs.forEachIndexed { idx, cert ->
            KeyStoreUtils.addCertificate(
                keyStore,
                "trusted-$idx-${cert.serialNumber}",
                cert
            )
        }

        val tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(keyStore)

        val trustManager = tmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, tmf.trustManagers, SecureRandom())
        }

        return TlsContext(sslContext, trustManager)
    }

    fun captureOnly(): TlsContext {
        val capturingTrustManager = CapturingTrustManager()

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(
                null,
                arrayOf<TrustManager>(capturingTrustManager),
                SecureRandom()
            )
        }

        return TlsContext(sslContext, capturingTrustManager)
    }

}
