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
package com.redhat.devtools.gateway.openshift

import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.auth.tls.CertificateSource
import com.redhat.devtools.gateway.auth.tls.PemUtils
import com.redhat.devtools.gateway.auth.tls.TlsContext
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.Config
import io.kubernetes.client.util.credentials.AccessTokenAuthentication
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.io.path.readText

/**
 * Interface for building OpenShift API clients.
 */
interface OpenShiftClientBuilder {
    fun build(): ApiClient
    fun readTimeout(timeout: Long, unit: TimeUnit): OpenShiftClientBuilder
}

/**
 * Base class for building OpenShift API clients.
 * Provides shared read timeout handling via the [applyReadTimeout] helper.
 */
abstract class BaseClientBuilder : OpenShiftClientBuilder {
    private var readTimeoutSeconds: Long = 0

    override fun readTimeout(timeout: Long, unit: TimeUnit): OpenShiftClientBuilder {
        this.readTimeoutSeconds = unit.toSeconds(timeout)
        return this
    }

    protected fun applyReadTimeout(client: ApiClient): ApiClient {
        if (readTimeoutSeconds > 0) {
            client.httpClient = client.httpClient.newBuilder()
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .build()
        }
        return client
    }
}

/**
 * Builder for default API clients (no server/token specified).
 * Reads kubeconfig files and creates an ApiClient from them.
 */
class DefaultClientBuilder(
    private val configUtils: KubeConfigUtils
) : BaseClientBuilder() {
    override fun build(): ApiClient {
        val paths = configUtils.getAllConfigFiles()
        if (paths.isEmpty()) {
            thisLogger().debug("No effective kubeconfig found. Falling back to default ApiClient.")
            return ClientBuilder.defaultClient()
        }

        return try {
            val allConfigs = configUtils.getAllConfigs(paths)
            if (allConfigs.isEmpty()) {
                thisLogger().debug("No valid kubeconfig content found. Falling back to default ApiClient.")
                return ClientBuilder.defaultClient()
            }

            val kubeConfig = configUtils.mergeConfigs(allConfigs)
            val client = ClientBuilder.kubeconfig(kubeConfig).build()
            applyReadTimeout(client)
        } catch (e: Exception) {
            thisLogger().debug(
                "Failed to build effective Kube config from discovered files due to error: ${e.message}. " +
                    "Falling back to the default ApiClient."
            )
            ClientBuilder.defaultClient()
        }
    }
}

/**
 * Builder for token-authenticated API clients.
 * Creates a kubeconfig from the provided server and token, then builds an ApiClient.
 */
class TokenClientBuilder(
    private val server: String,
    private val token: String
) : BaseClientBuilder() {
    override fun build(): ApiClient {
        val kubeConfig = createKubeConfig(server, null, token.toCharArray(), null, null)
        val client = Config.fromConfig(kubeConfig)
        return applyReadTimeout(client)
    }
}

/**
 * Builder for TLS-authenticated API clients.
 * Handles both token-based and client-certificate authentication with TlsContext.
 */
class TlsClientBuilder(
    private val server: String,
    private val token: String? = null,
    private val clientCert: CertificateSource? = null,
    private val clientKey: CertificateSource? = null,
    private val tlsContext: TlsContext
) : BaseClientBuilder() {
    override fun build(): ApiClient {
        validateAuthInputs()
        return if (clientCert != null && clientKey != null) {
            createWithClientCertFromTls(server, clientCert, clientKey, tlsContext)
        } else {
            createWithTokenFromTls(server, token!!, tlsContext)
        }
    }

    private fun validateAuthInputs() {
        val usingToken = token?.isNotEmpty() == true
        val usingClientCert = clientCert != null && clientKey != null
        require(usingToken.xor(usingClientCert)) {
            "Provide either token OR clientCert + clientKey."
        }
    }

    /**
     * Builds a client-certificate-authenticated client using the same [TlsContext] SSL stack as OAuth.
     */
    internal fun createWithClientCertFromTls(
        server: String,
        clientCert: CertificateSource,
        clientKey: CertificateSource,
        tlsContext: TlsContext
    ): ApiClient {
        val trustManager = tlsContext.trustManager
        val sslContext = createSSLContext(trustManager, true, clientCert, clientKey)
        val client = ApiClient(createHttpClient(sslContext, trustManager))
        client.basePath = normalizeBasePath(server)
        return applyReadTimeout(client)
    }

    private fun createSSLContext(
        trustManager: X509TrustManager,
        usingClientCert: Boolean,
        clientCert: CertificateSource?,
        clientKey: CertificateSource?
    ): SSLContext {
        val keyManagers: Array<KeyManager>? =
            if (usingClientCert && clientCert != null && clientKey != null) {
                createKeyManagers(clientCert, clientKey)
            } else {
                null
            }

        return SSLContext.getInstance("TLS").apply {
            init(
                keyManagers,
                arrayOf(trustManager),
                SecureRandom()
            )
        }
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

    /**
     * Resolves CertificateSource to actual content.
     * If it's a file path, reads the file. Otherwise returns the value.
     */
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

    private fun createHttpClient(sslContext: SSLContext, trustManager: X509TrustManager): OkHttpClient {
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .connectTimeout(DEFAULT_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(DEFAULT_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Match OAuth HttpClient (HTTP/1.1); some clusters hang on HTTP/2.
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    /**
     * Builds a token-authenticated client using the same [TlsContext] SSL stack as OAuth.
     * Avoids [io.kubernetes.client.util.Config.fromConfig], which applies JVM default trust via [ApiClient.applySslSettings].
     */
    internal fun createWithTokenFromTls(server: String, token: String, tlsContext: TlsContext): ApiClient {
        val client = ApiClient(createHttpClient(tlsContext.sslContext, tlsContext.trustManager))
        client.basePath = normalizeBasePath(server)
        AccessTokenAuthentication(token.trim()).provide(client)
        return applyReadTimeout(client)
    }
}

private const val DEFAULT_HTTP_TIMEOUT_SECONDS = 30L

private fun normalizeBasePath(server: String): String = server.trim().removeSuffix("/")


