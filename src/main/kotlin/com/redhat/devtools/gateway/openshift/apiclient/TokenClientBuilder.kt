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
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.Config
import io.kubernetes.client.util.KubeConfig

private const val userName = "openshift_user"
private const val contextName = "openshift_context"
private const val clusterName = "openshift_cluster"

/**
 * Builder for token-authenticated API clients.
 * Creates a kubeconfig from the provided server and token, then builds an ApiClient.
 */
class TokenClientBuilder(
    private val server: String,
    private val token: String
) : BaseClientBuilder() {
    override fun build(): ApiClient {
        val kubeConfig = createKubeConfig(null, token.toCharArray())
        val client = Config.fromConfig(kubeConfig)
        return applyReadTimeout(client)
    }

    private fun createKubeConfig(
        certificateAuthority: CertificateSource?,
        token: CharArray?
    ): KubeConfig {
        val usingToken = token?.isNotEmpty() == true
        val usingClientCert = false

        require(usingToken) {
            "Provide either token OR clientCert + clientKey."
        }

        val clusterEntry = createCluster(certificateAuthority)
        val userEntry = createUser(usingToken, token)
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

    private fun createUser(usingToken: Boolean, token: CharArray?): Map<String, Any> {
        val user = mutableMapOf<String, Any>()

        if (usingToken && token != null) {
            setToken(token, user)
        }

        return mapOf(
            "name" to userName,
            "user" to user
        )
    }

    private fun setToken(token: CharArray, user: MutableMap<String, Any>) {
        user["token"] = String(token).trim()
    }
}
