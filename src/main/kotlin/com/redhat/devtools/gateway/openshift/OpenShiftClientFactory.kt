/*
 * Copyright (c) 2024-2025 Red Hat, Inc.
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
import com.redhat.devtools.gateway.openshift.kube.KubeConfigUtils
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.Config
import io.kubernetes.client.util.KubeConfig
import java.io.StringReader

class OpenShiftClientFactory(private val kubeConfigBuilder: KubeConfigUtils) {
    private val userName = "openshift_user"
    private val contextName = "openshift_context"
    private val clusterName = "openshift_cluster"
    
    private var lastUsedKubeConfig: KubeConfig? = null

    fun create(): ApiClient {
        val effectiveKubeConfig = kubeConfigBuilder.getMergedConfig()

        return if (effectiveKubeConfig != null) {
            try {
                val reader = StringReader(effectiveKubeConfig)
                val kubeConfig = KubeConfig.loadKubeConfig(reader)
                lastUsedKubeConfig = kubeConfig
                ClientBuilder.kubeconfig(kubeConfig).build()
            } catch (e: Exception) {
                thisLogger().debug("Failed to build effective Kube config from discovered files due to error: ${e.message}. Falling back to the default ApiClient.")
                lastUsedKubeConfig = null
                ClientBuilder.defaultClient()
            }
        } else {
            thisLogger().debug("No effective kubeconfig found. Falling back to default ApiClient.")
            lastUsedKubeConfig = null
            ClientBuilder.defaultClient()
        }
    }

    fun create(server: String, token: CharArray): ApiClient {
        val kubeConfig = createKubeConfig(server, token)
        lastUsedKubeConfig = kubeConfig
        return Config.fromConfig(kubeConfig)
    }
    
    fun isTokenAuthUsed(): Boolean {
        return lastUsedKubeConfig?.let {
            KubeConfigUtils.isTokenAuthUsed(it)
        } ?: false
    }

    private fun createKubeConfig(server: String, token: CharArray): KubeConfig {
        val cluster = mapOf(
            "name" to clusterName,
            "cluster" to mapOf(
                "server" to server.trim(),
                "insecure-skip-tls-verify" to true
            )
        )

        val user = mapOf(
            "name" to userName,
            "user" to mapOf(
                "token" to String(token).trim()
            )
        )

        val context = mapOf(
            "name" to contextName,
            "context" to mapOf(
                "cluster" to clusterName,
                "user" to userName
            )
        )

        val kubeConfig  = KubeConfig(arrayListOf(context), arrayListOf(cluster), arrayListOf(user))
        kubeConfig.setContext(contextName)

        return kubeConfig
    }
}
