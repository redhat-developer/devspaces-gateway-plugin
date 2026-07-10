/*
 * Copyright (c) 2024-2026 Red Hat, Inc.
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
import io.kubernetes.client.util.KubeConfig
import java.io.IOException
import kotlin.io.path.readText
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class OpenShiftClientFactory(private val configUtils: KubeConfigUtils) {
    private val userName = "openshift_user"
    private val contextName = "openshift_context"
    private val clusterName = "openshift_cluster"

    private var lastUsedKubeConfig: KubeConfig? = null

    class Builder internal constructor(
        private val factory: OpenShiftClientFactory,
        private val server: String,
        private val token: String
    ) {
        private var readTimeoutSeconds: Long = 0

        fun readTimeout(timeout: Long, unit: TimeUnit): Builder {
            this.readTimeoutSeconds = unit.toSeconds(timeout)
            return this
        }

        fun build(): ApiClient {
            val client = factory.create(server, token)
            if (readTimeoutSeconds > 0) {
                client.httpClient = client.httpClient.newBuilder()
                    .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                    .build()
            }
            return client
        }
    }

    fun builder(server: String, token: String): Builder {
        return Builder(this, server, token)
    }

    fun create(): ApiClient {
        val paths = configUtils.getAllConfigFiles()
        if (paths.isEmpty()) {
            thisLogger().debug("No effective kubeconfig found. Falling back to default ApiClient.")
            lastUsedKubeConfig = null
            return ClientBuilder.defaultClient()
        }

        return try {
            val allConfigs = configUtils.getAllConfigs(paths)
            if (allConfigs.isEmpty()) {
                thisLogger().debug("No valid kubeconfig content found. Falling back to default ApiClient.")
                lastUsedKubeConfig = null
                return ClientBuilder.defaultClient()
            }

            val kubeConfig = configUtils.mergeConfigs(allConfigs)
            lastUsedKubeConfig = kubeConfig
            ClientBuilder.kubeconfig(kubeConfig).build()
        } catch (e: Exception) {
            thisLogger().debug("Failed to build effective Kube config from discovered files due to error: ${e.message}. Falling back to the default ApiClient.")
            lastUsedKubeConfig = null
            ClientBuilder.defaultClient()
        }
    }

    fun create(server: String, token: String): ApiClient {
        val kubeConfig = createKubeConfig(server, null, token.toCharArray(), null, null)
        lastUsedKubeConfig = kubeConfig
        return Config.fromConfig(kubeConfig)
    }

    fun create(
        server: String,
        certificateAuthority: CertificateSource? = null,
        token: CharArray? = null,
        clientCert: CertificateSource? = null,
        clientKey: CertificateSource? = null,
        tlsContext: TlsContext
    ): ApiClient {

        val usingToken = token?.isNotEmpty() == true
        val usingClientCert = clientCert != null
                && clientKey != null

        require(usingToken.xor(usingClientCert)) {
            "Provide either token OR clientCert + clientKey."
        }

        val kubeConfig = createKubeConfig(server, certificateAuthority, token, clientCert, clientKey)
        lastUsedKubeConfig = kubeConfig

        val client = Config.fromConfig(kubeConfig)
        val trustManager: X509TrustManager = createTrustManager(certificateAuthority, tlsContext)
        val sslContext = createSSLContext(trustManager, usingClientCert, clientCert, clientKey)
        client.httpClient = client.httpClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()

        return client
    }

    private fun createTrustManager(
        certificateAuthority: CertificateSource?,
        tlsContext: TlsContext
    ): X509TrustManager = if (certificateAuthority != null) {
        createTrustManager(certificateAuthority)
    } else {
        tlsContext.trustManager
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

    private fun createTrustManager(
        caSource: CertificateSource
    ): X509TrustManager {

        val caContent = resolve(caSource)
        val caCert = PemUtils.parseCertificate(caContent)

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setCertificateEntry("ca", caCert)

        val tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(keyStore)

        return tmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
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

    private fun createKubeConfig(
        server: String,
        certificateAuthority: CertificateSource? = null,
        token: CharArray? = null,
        clientCert: CertificateSource? = null,
        clientKey: CertificateSource? = null
    ): KubeConfig {

        val usingToken = token?.isNotEmpty() == true
        val usingClientCert = clientCert != null && clientKey != null

        require(usingToken.xor(usingClientCert)) {
            "Provide either token OR clientCert + clientKey."
        }

        val clusterEntry = createCluster(server, certificateAuthority)
        val userEntry = createUser(usingToken, token, clientCert, clientKey)
        val contextEntry = mapOf(
            "name" to contextName,
            "context" to mapOf(
                "cluster" to clusterName,
                "user" to userName
            )
        )

        val kubeConfig = KubeConfig(arrayListOf(contextEntry), arrayListOf(clusterEntry), arrayListOf(userEntry))
        kubeConfig.setContext(contextName)

        return kubeConfig
    }

    private fun createCluster(
        server: String,
        certificateAuthority: CertificateSource?
    ): Map<String, Any> {
        val cluster = mutableMapOf<String, Any>(
            "server" to server.trim()
        )

        certificateAuthority?.let { ca ->
            if (ca.isFilePath) {
                cluster["certificate-authority"] = ca.value.trim()
            } else {
                cluster["certificate-authority-data"] = PemUtils.toBase64(ca.value.trim())
            }
        }

        val clusterEntry = mapOf(
            "name" to clusterName,
            "cluster" to cluster
        )
        return clusterEntry
    }

    private fun createUser(usingToken: Boolean, token: CharArray?, clientCert: CertificateSource?, clientKey: CertificateSource?): Map<String, Any> {
        val user = mutableMapOf<String, Any>()

        if (usingToken
            && token != null) {
            setToken(token, user)
        } else {
            setClientCertificates(clientCert, clientKey, user)
        }

        return mapOf(
            "name" to userName,
            "user" to user
        )
    }

    private fun setToken(token: CharArray, user: MutableMap<String, Any>) {
        user["token"] = String(token).trim()
    }

    private fun setClientCertificates(
        clientCert: CertificateSource?,
        clientKey: CertificateSource?,
        user: MutableMap<String, Any>
        ) {
        clientCert?.let { cert ->
            if (cert.isFilePath) {
                user["client-certificate"] = cert.value.trim()
            } else {
                user["client-certificate-data"] = PemUtils.toBase64(cert.value.trim())
            }
        }
        clientKey?.let { key ->
            if (key.isFilePath) {
                user["client-key"] = key.value.trim()
            } else {
                user["client-key-data"] = PemUtils.toBase64(key.value.trim())
            }
        }
    }
}
