/*
 * Copyright (c) 2024 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.github.devspaces.gateway.openshift

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.Config
import io.kubernetes.client.util.KubeConfig

class OpenShiftClientFactory(private val host: String, private val port: String, private val token: CharArray) {
    private val userName = "openshift_user"
    private val contextName = "openshift_context"
    private val clusterName = "openshift_cluster"

    fun create(): ApiClient {
        val kubeConfig = createKubeConfig()
        return Config.fromConfig(kubeConfig)
    }

    private fun createKubeConfig(): KubeConfig {
        val cluster = mapOf(
            "name" to clusterName,
            "cluster" to mapOf(
                "server" to String.format("https://%s:%s", host.trim(), port.trim()),
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