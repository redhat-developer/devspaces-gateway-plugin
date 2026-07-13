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
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private const val SSL_PROTOCOL = "TLS"

object SslContextFactory {

    fun empty(): TlsContext =
        fromTrustedCerts(emptyList())

    fun insecure(): TlsContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance(SSL_PROTOCOL).apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }

        return TlsContext(sslContext, trustAll, isInsecure = true)
    }

    /** TLS context that trusts the JVM default certificate authorities. */
    fun fromSystemTrust(): TlsContext {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)

        val trustManager = tmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

        val sslContext = SSLContext.getInstance(SSL_PROTOCOL).apply {
            init(null, tmf.trustManagers, SecureRandom())
        }

        return TlsContext(sslContext, trustManager, usesSystemTrust = true)
    }

    fun fromTrustedCerts(certs: List<X509Certificate>): TlsContext {
        val defaultTrustManager = defaultTrustManager()
        if (certs.isEmpty()) {
            val sslContext = SSLContext.getInstance(SSL_PROTOCOL).apply {
                init(null, arrayOf<TrustManager>(defaultTrustManager), SecureRandom())
            }
            return TlsContext(sslContext, defaultTrustManager, usesSystemTrust = true)
        }

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

        val customTrustManager = tmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

        val trustManager = CompositeX509TrustManager(
            defaultTrustManager,
            customTrustManager,
        )

        val sslContext = SSLContext.getInstance(SSL_PROTOCOL).apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }

        return TlsContext(sslContext, trustManager)
    }

    fun captureOnly(failIfUntrusted: Boolean = true): TlsContext {
        val capturingTrustManager = CapturingTrustManager(failIfUntrusted)

        val sslContext = SSLContext.getInstance(SSL_PROTOCOL).apply {
            init(
                null,
                arrayOf<TrustManager>(capturingTrustManager),
                SecureRandom()
            )
        }

        return TlsContext(sslContext, capturingTrustManager, isCapturingProbe = true)
    }

}

private fun defaultTrustManager(): X509TrustManager {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(null as java.security.KeyStore?)

    return tmf.trustManagers
        .filterIsInstance<X509TrustManager>()
        .first()
}

private class CompositeX509TrustManager(
    private val defaultTrustManager: X509TrustManager,
    private val customTrustManager: X509TrustManager,
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        checkTrusted(
            { defaultTrustManager.checkClientTrusted(chain, authType) },
            { customTrustManager.checkClientTrusted(chain, authType) },
        )
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        checkTrusted(
            { defaultTrustManager.checkServerTrusted(chain, authType) },
            { customTrustManager.checkServerTrusted(chain, authType) },
        )
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        defaultTrustManager.acceptedIssuers + customTrustManager.acceptedIssuers

    private fun checkTrusted(defaultCheck: () -> Unit, customCheck: () -> Unit) {
        try {
            defaultCheck()
        } catch (defaultFailure: CertificateException) {
            try {
                customCheck()
            } catch (_: CertificateException) {
                throw defaultFailure
            }
        }
    }
}
