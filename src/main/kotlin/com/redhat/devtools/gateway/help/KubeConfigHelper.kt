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
package com.redhat.devtools.gateway.help

import org.yaml.snakeyaml.Yaml
import java.io.File

class KubeConfigHelper(kubeConfigPath: String? = null) {
    private val clusters: Map<String, String> // clusterName -> server
    private val users: Map<String, String>    // userName -> token
    private val contexts: Map<String, Pair<String, String>> // contextName -> (clusterName, userName)

    init {
        val path = kubeConfigPath ?: System.getenv("KUBECONFIG")
        ?: (System.getProperty("user.home") + "/.kube/config")

        val configMap = Yaml().load<Map<String, Any>>(File(path).inputStream())

        val clusterList = configMap["clusters"] as? List<*> ?: emptyList<Any>()
        clusters = clusterList.mapNotNull { entry ->
            (entry as? Map<*, *>)?.let {
                val name = it["name"] as? String ?: return@mapNotNull null
                val cluster = it["cluster"] as? Map<*, *> ?: return@mapNotNull null
                val server = cluster["server"] as? String ?: return@mapNotNull null
                name to server
            }
        }.toMap()

        val userList = configMap["users"] as? List<*> ?: emptyList<Any>()
        users = userList.mapNotNull { entry ->
            (entry as? Map<*, *>)?.let {
                val name = it["name"] as? String ?: return@mapNotNull null
                val user = it["user"] as? Map<*, *> ?: return@mapNotNull null
                val token = user["token"] as? String ?: ""
                name to token
            }
        }.toMap()

        val contextList = configMap["contexts"] as? List<*> ?: emptyList<Any>()
        contexts = contextList.mapNotNull { entry ->
            (entry as? Map<*, *>)?.let {
                val name = it["name"] as? String ?: return@mapNotNull null
                val ctx = it["context"] as? Map<*, *> ?: return@mapNotNull null
                val clusterName = ctx["cluster"] as? String ?: return@mapNotNull null
                val userName = ctx["user"] as? String ?: return@mapNotNull null
                name to (clusterName to userName)
            }
        }.toMap()
    }

    fun getServers(): List<String> = clusters.values.toList()

    fun getTokenForServer(server: String): String? {
        // Find cluster name for this server
        val clusterName = clusters.entries.find { it.value == server }?.key ?: return null
        // Find context using this cluster
        val userName = contexts.values.find { it.first == clusterName }?.second ?: return null
        // Return token for that user
        return users[userName]
    }
}
