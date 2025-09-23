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
import java.nio.file.Paths

class InvalidKubeConfigException(message: String) : Exception(message)

object KubeConfigBuilder {
    private val yamlMapper = ObjectMapper(YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
    private val allClusters = mutableListOf<Map<String, Any>>()
    private val allContexts = mutableListOf<Map<String, Any>>()
    private val allUsers = mutableListOf<Map<String, Any>>()

    fun fromEnvVar(): String = fromConfigs(getKubeconfigEnvPaths())

    private fun fromDefault(): String = fromConfigs(getDefaultKubeconfigPath())

    @Suppress("UNCHECKED_CAST")
    private fun fromConfigs(files: List<File>): String {
        var selectedContextName: String? = null
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

        val finalConfig = createConfig(selectedContextName, preferences)
        return yamlMapper.writeValueAsString(finalConfig)
    }

    private fun createConfig(
        selectedContextName: String?,
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
    fun isTokenAuthUsed(): Boolean {
        return try {
            val users = allUsers as? List<Map<String, Any>> ?: return false
            val firstUser = users.firstOrNull() ?: return false
            val userDetails = firstUser["user"] as? Map<String, Any> ?: return false
            val hasDirectToken = userDetails.containsKey("token")
            val hasAuthProviderToken = (userDetails["auth-provider"] as? Map<String, Any>)?.containsKey("token") == true

            hasDirectToken || hasAuthProviderToken
        } catch (_: Exception) {
            false
        }
    }

    init {
        val envKubeConfig = System.getenv("KUBECONFIG")
        if (envKubeConfig != null) fromEnvVar() else fromDefault()
    }

    fun getClusters(): List<Cluster> {
        return allClusters.mapNotNull { cluster ->
            val clusterName = cluster["name"] as? String ?: return@mapNotNull null
            val clusterDetails = cluster["cluster"] as? Map<*, *>
            val serverUrl = clusterDetails?.get("server") as? String ?: return@mapNotNull null
            Cluster(clusterName, serverUrl)
        }
    }

    fun getTokenForCluster(name: String): String? {
        val userName = allContexts.find { ctx ->
            val context = ctx["context"] as? Map<*, *>
            context?.get("cluster") == name
        }?.get("context")?.let { it as? Map<*, *> }?.get("user") as? String ?: return null

        val token = allUsers.find { user -> user["name"] == userName }?.get("user")
            ?.let { it as? Map<*, *> }?.get("token") as? String

        return token
    }
}