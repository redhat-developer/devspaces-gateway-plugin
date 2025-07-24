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
import java.io.StringReader
import java.nio.file.Paths

class InvalidKubeConfigException(message: String) : Exception(message)

object KubeConfigBuilder {
    private val yamlMapper = ObjectMapper(YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))

    fun fromEnvVar(): String = fromConfigs(getKubeconfigEnvPaths())

    fun fromDefault(): String = fromConfigs(getDefaultKubeconfigPath())

    @Suppress("UNCHECKED_CAST")
    fun fromConfigs(files: List<File>): String {
        var selectedContextName: String? = null
        val allClusters = mutableListOf<Map<String, Any>>()
        val allContexts = mutableListOf<Map<String, Any>>()
        val allUsers = mutableListOf<Map<String, Any>>()
        var preferences: Map<String, Any>? = null

        for (file in files) {
            if (!file.exists()) continue

            val config = yamlMapper.readValue(file, Map::class.java) as? Map<String, Any> ?: continue

            (config["clusters"] as? List<*>)?.filterIsInstance<Map<String, Any>>()?.let { allClusters.addAll(it) }
            (config["contexts"] as? List<*>)?.filterIsInstance<Map<String, Any>>()?.let { allContexts.addAll(it) }
            (config["users"] as? List<*>)?.filterIsInstance<Map<String, Any>>()?.let { allUsers.addAll(it) }

            if (selectedContextName == null) {
                selectedContextName = config["current-context"] as? String
            }

            if (preferences == null) {
                preferences = config["preferences"] as? Map<String, Any>
            }
        }

        val finalConfig = createConfig(selectedContextName, allContexts, allClusters, allUsers, preferences)
        return yamlMapper.writeValueAsString(finalConfig)
    }

    private fun createConfig(
        selectedContextName: String?,
        allContexts: MutableList<Map<String, Any>>,
        allClusters: MutableList<Map<String, Any>>,
        allUsers: MutableList<Map<String, Any>>,
        preferences: Map<String, Any>?
    ): MutableMap<String, Any> {
        if (selectedContextName == null) {
            throw InvalidKubeConfigException("No current-context found in provided kubeconfigs.")
        }

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
        return finalConfig
    }

    private fun getKubeconfigEnvPaths(): List<File> =
        System.getenv("KUBECONFIG")
            ?.split(File.pathSeparator)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.map(::File)
            ?: emptyList()

    private fun getDefaultKubeconfigPath(): List<File> =
        listOfNotNull(
            Paths.get(System.getProperty("user.home"), ".kube", "config")
                .takeIf { it.toFile().exists() && it.toFile().isFile }
                ?.toFile()
        )

    @Suppress("UNCHECKED_CAST")
    fun isTokenAuthUsed(): Boolean = try {
        val envKubeConfig = System.getenv("KUBECONFIG")
        val kubeConfigYaml = if (envKubeConfig != null) fromEnvVar() else fromDefault()

        val kubeConfigMap = yamlMapper.readValue(StringReader(kubeConfigYaml), Map::class.java) as? Map<String, Any> ?: return false
        val users = kubeConfigMap["users"] as? List<Map<String, Any>> ?: return false
        val firstUser = users.firstOrNull() ?: return false
        val userDetails = firstUser["user"] as? Map<String, Any> ?: return false
        val hasDirectToken = userDetails.containsKey("token")
        val hasAuthProviderToken = (userDetails["auth-provider"] as? Map<String, Any>)?.containsKey("token") == true

        hasDirectToken || hasAuthProviderToken
    } catch (_: Exception) {
        false
    }
}