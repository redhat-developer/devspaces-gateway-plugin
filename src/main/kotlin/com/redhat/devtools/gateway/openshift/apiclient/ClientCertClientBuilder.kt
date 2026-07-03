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
package com.redhat.devtools.gateway.openshift.apiclient

import com.redhat.devtools.gateway.auth.tls.CertificateSource
import com.redhat.devtools.gateway.auth.tls.PemUtils
import com.redhat.devtools.gateway.auth.tls.TlsContext
import io.kubernetes.client.openapi.ApiClient
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.io.path.readText

/**
 * Builder for client-certificate-authenticated API clients.
 * Uses the wizard-negotiated [TlsContext] for server CA trust and adds the client certificate.
 */
class ClientCertClientBuilder(
    private val server: String,
    private val clientCert: CertificateSource,
    private val clientKey: CertificateSource,
    private val tlsContext: TlsContext
) : BaseClientBuilder() {
    override fun build(): ApiClient {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(
                createKeyManagers(clientCert, clientKey),
                arrayOf(tlsContext.trustManager),
                SecureRandom()
            )
        }
        val client = ApiClient(createHttpClient(sslContext, tlsContext.trustManager))
        client.basePath = normalizeBasePath(server)
        return applyReadTimeout(client)
    }

    private fun createKeyManagers(
        certSource: CertificateSource,
        keySource: CertificateSource
    ): Array<KeyManager> {

        val certContent = resolve(certSource)
        val keyContent = resolve(keySource)

        val certificate = PemUtils.parseCertificate(certContent)
        val privateKey = PemUtils.parsePrivateKey(keyContent)

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null)

        keyStore.setKeyEntry(
            "client",
            privateKey,
            CharArray(0),
            arrayOf(certificate)
        )

        val kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm()
        )
        kmf.init(keyStore, CharArray(0))

        return kmf.keyManagers
    }

    private fun resolve(source: CertificateSource): String {
        return if (source.isFilePath) {
            try {
                source.toPath().readText()
            } catch (e: Exception) {
                throw IOException("Failed to read certificate file: ${source.value}", e)
            }
        } else {
            source.value
        }
    }
}
