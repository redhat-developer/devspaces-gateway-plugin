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

import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.openapi.ApiClient
import java.net.Proxy

/**
 * Builder for API clients used in the deep-link flow (no wizard).
 * Reads kubeconfig files and creates an ApiClient from them.
 */
class LinkClientBuilder(
    private val configUtils: KubeConfigUtils
) : BaseClientBuilder() {
    override fun build(): ApiClient {
        val paths = configUtils.getAllConfigFiles()
        if (paths.isEmpty()) {
            thisLogger().debug("No effective kubeconfig found. Falling back to default ApiClient.")
            return bypassProxy(ClientBuilder.defaultClient())
        }

        return try {
            val allConfigs = configUtils.getAllConfigs(paths)
            if (allConfigs.isEmpty()) {
                thisLogger().debug("No valid kubeconfig content found. Falling back to default ApiClient.")
                return bypassProxy(ClientBuilder.defaultClient())
            }

            val kubeConfig = configUtils.mergeConfigs(allConfigs)
            val client = bypassProxy(ClientBuilder.kubeconfig(kubeConfig).build())
            applyReadTimeout(client)
        } catch (e: Exception) {
            thisLogger().debug(
                "Failed to build effective Kube config from discovered files due to error: ${e.message}. " +
                    "Falling back to the default ApiClient."
            )
            bypassProxy(ClientBuilder.defaultClient())
        }
    }

    private fun bypassProxy(client: ApiClient): ApiClient {
        client.httpClient = client.httpClient.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .build()
        return client
    }
}
