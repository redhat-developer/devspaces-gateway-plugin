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
import com.redhat.devtools.gateway.openshift.kube.InvalidKubeConfigException
import com.redhat.devtools.gateway.openshift.kube.KubeConfigBuilder
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.Config
import io.kubernetes.client.util.KubeConfig
import java.io.StringReader

class OpenShiftClientFactory() {
    private val userName = "openshift_user"
    private val contextName = "openshift_context"
    private val clusterName = "openshift_cluster"

    fun create(): ApiClient {
        val envKubeConfig = System.getenv("KUBECONFIG")
        if (envKubeConfig != null) {
            try {
                val effectiveConfigYaml = KubeConfigBuilder.buildEffectiveKubeConfig()
                val reader = StringReader(effectiveConfigYaml)
                val kubeConfig = KubeConfig.loadKubeConfig(reader)
                return ClientBuilder.kubeconfig(kubeConfig).build()
            } catch (err: InvalidKubeConfigException) {
                thisLogger().debug("Failed to build an effective Kube config from `KUBECONFIG` due to error: ${err.message}. Falling back to the default ApiClient.")
            }
        }

        return ClientBuilder.defaultClient()
    }

    fun create(server: String, token: CharArray): ApiClient {
        val kubeConfig = createKubeConfig(server, token)
        return Config.fromConfig(kubeConfig)
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