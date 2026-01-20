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
import com.redhat.devtools.gateway.auth.tls.PemUtils
import com.redhat.devtools.gateway.auth.tls.TlsContext
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.Config
import io.kubernetes.client.util.KubeConfig
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class OpenShiftClientFactory(private val configUtils: KubeConfigUtils) {
    private val userName = "openshift_user"
    private val contextName = "openshift_context"
    private val clusterName = "openshift_cluster"

    private var lastUsedKubeConfig: KubeConfig? = null

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

    fun create(
        server: String,
        certificateAuthorityData: CharArray? = null,
        token: CharArray? = null,
        clientCertData: CharArray? = null,
        clientKeyData: CharArray? = null,
        tlsContext: TlsContext
    ): ApiClient {

        val usingToken = token != null && token.isNotEmpty()
        val usingClientCert = clientCertData != null && clientCertData.isNotEmpty()
                && clientKeyData != null && clientKeyData.isNotEmpty()
        val usingCertificateAuthorityData = certificateAuthorityData != null && certificateAuthorityData.isNotEmpty()

        require(usingToken.xor(usingClientCert)) {
            "Provide either token OR clientCertData + clientKeyData."
        }

        val kubeConfig = createKubeConfig(server, certificateAuthorityData, token, clientCertData, clientKeyData)
        lastUsedKubeConfig = kubeConfig

        val client = Config.fromConfig(kubeConfig)

        val trustManager: X509TrustManager =
            if (usingCertificateAuthorityData) {
                buildTrustManagerFromCaData(certificateAuthorityData)
            } else {
                tlsContext.trustManager
            }

        val keyManagers: Array<KeyManager>? =
            if (usingClientCert) {
                buildKeyManagers(clientCertData, clientKeyData)
            } else {
                null
            }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(
            keyManagers,
            arrayOf(trustManager),
            SecureRandom()
        )

        client.httpClient = client.httpClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()

        return client
    }

    private fun buildTrustManagerFromCaData(
        caData: CharArray
    ): X509TrustManager {

        val decoded = Base64.getDecoder().decode(String(caData))

        val certificateFactory = CertificateFactory.getInstance("X.509")
        val caCert = certificateFactory.generateCertificate(
            ByteArrayInputStream(decoded)
        )

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

    private fun buildKeyManagers(
        certData: CharArray,
        keyData: CharArray
    ): Array<KeyManager> {

        val certBytes = Base64.getDecoder().decode(String(certData))
        val keyBytes = Base64.getDecoder().decode(String(keyData))

        val certificateFactory = CertificateFactory.getInstance("X.509")
        val certificate = certificateFactory.generateCertificate(
            ByteArrayInputStream(certBytes)
        )

        val privateKey = PemUtils.parsePrivateKey(String(keyBytes))

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

    private fun createKubeConfig(server: String, certificateAuthorityData: CharArray? = null, token: CharArray? = null,
        clientCertData: CharArray? = null, clientKeyData: CharArray? = null
    ): KubeConfig {

        val usingToken = token != null
        val usingClientCert = clientCertData != null && clientKeyData != null

        require(usingToken.xor(usingClientCert)) {
            "Provide either token OR clientCertData + clientKeyData."
        }

        val cluster = mutableMapOf<String, Any>(
            "server" to server.trim()
        )

        val caData = certificateAuthorityData
            ?.let { String(it).trim() }
            ?.takeIf { it.isNotEmpty() }

        if (caData != null) {
            cluster["certificate-authority-data"] = caData
        }

        val clusterEntry = mapOf(
            "name" to clusterName,
            "cluster" to cluster
        )

        val userAuth = mutableMapOf<String, Any>()

        if (usingToken) {
            userAuth["token"] = String(token).trim()
        } else {
            userAuth["client-certificate-data"] =
                String(clientCertData!!).trim()
            userAuth["client-key-data"] =
                String(clientKeyData!!).trim()
        }

        val userEntry = mapOf(
            "name" to userName,
            "user" to userAuth
        )

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
}