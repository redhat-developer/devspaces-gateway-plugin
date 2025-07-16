/*
 * Copyright (c) 2025 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.openshift.kube

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import java.io.File
import java.util.*

class InvalidKubeConfigException(message: String) : Exception(message)

class KubeConfigBuilder {
    companion object {
        fun buildEffectiveKubeConfig(): String {
            val files: List<File> = getKubeconfigPaths()

            val mapper = ObjectMapper(YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))

            var selectedContextName: String? = null
            val allClusters = mutableListOf<Map<String, Any>>()
            val allContexts = mutableListOf<Map<String, Any>>()
            val allUsers = mutableListOf<Map<String, Any>>()
            var preferences: Map<String, Any>? = null

            for (file in files) {
                if (!file.exists()) continue

                @Suppress("UNCHECKED_CAST")
                val config = mapper.readValue(file, Map::class.java) as Map<String, Any>

                (config["clusters"] as? List<*>)?.filterIsInstance<Map<String, Any>>()?.let { allClusters.addAll(it) }
                (config["contexts"] as? List<*>)?.filterIsInstance<Map<String, Any>>()?.let { allContexts.addAll(it) }
                (config["users"] as? List<*>)?.filterIsInstance<Map<String, Any>>()?.let { allUsers.addAll(it) }

                // Pick first encountered current-context
                if (selectedContextName == null) {
                    selectedContextName = config["current-context"] as? String
                }

                if (preferences == null && config["preferences"] is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    preferences = config["preferences"] as Map<String, Any>
                }
            }

            if (selectedContextName == null) {
                error("No current-context found in provided kubeconfigs.")
            }

            // Filter context
            val selectedContext = allContexts.find { it["name"] == selectedContextName }
                ?: throw InvalidKubeConfigException("current-context '$selectedContextName' not found in merged contexts")

            val contextObj = selectedContext["context"] as? Map<*, *>
                ?: throw InvalidKubeConfigException("Invalid context structure")
            val clusterName = contextObj["cluster"] as? String
                ?: throw InvalidKubeConfigException("Missing cluster in context")
            val userName = contextObj["user"] as? String
                ?: throw InvalidKubeConfigException("Missing user in context")

            val selectedCluster = allClusters.find { it["name"] == clusterName }
                ?: throw InvalidKubeConfigException("Cluster '$clusterName' referenced in current-context not found")

            val selectedUser = allUsers.find { it["name"] == userName }
                ?: throw InvalidKubeConfigException("User '$userName' referenced in current-context not found")

            val finalConfig = mutableMapOf(
                "apiVersion" to "v1",
                "kind" to "Config",
                "current-context" to selectedContextName,
                "clusters" to listOf(selectedCluster),
                "contexts" to listOf(selectedContext),
                "users" to listOf(selectedUser)
            )

            if (preferences != null) {
                finalConfig["preferences"] = preferences
            }

            return mapper.writeValueAsString(finalConfig)
        }

        private fun isWindows(): Boolean {
            return System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win")
        }

        private fun getKubeconfigPaths(): List<File> {
            val kubeConfigEnv = System.getenv("KUBECONFIG") ?: return emptyList()
            val separator = if (isWindows()) ";" else ":"

            return kubeConfigEnv
                .split(separator)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { File(it) }
        }
    }
}