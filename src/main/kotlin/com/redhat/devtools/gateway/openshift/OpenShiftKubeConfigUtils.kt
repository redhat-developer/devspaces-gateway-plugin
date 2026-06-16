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

import com.redhat.devtools.gateway.auth.tls.CertificateSource
import com.redhat.devtools.gateway.auth.tls.PemUtils
import io.kubernetes.client.util.KubeConfig

private val userName = "openshift_user"
private val contextName = "openshift_context"
private val clusterName = "openshift_cluster"

/**
 * Creates a kubeconfig from the provided parameters.
 */
internal fun createKubeConfig(
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
