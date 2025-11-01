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

import com.intellij.openapi.diagnostic.thisLogger
import io.kubernetes.client.util.KubeConfig
import java.io.File
import java.io.StringReader
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

object KubeConfigUtils {

    private val logger = thisLogger<KubeConfigUtils>()

    fun isTokenAuthUsed(kubeConfig: KubeConfig): Boolean {
        return KubeConfigNamedUser.hasTokenAuth(kubeConfig)
    }

    fun getClusters(kubeconfigs: List<Path>): List<Cluster> {
        logger.info("Getting clusters from kubeconfig paths: $kubeconfigs")
        val allKubeConfigs = toKubeConfigs(kubeconfigs)
        logger.info("Loaded ${allKubeConfigs.size} kubeconfig files")
        val clusters = allKubeConfigs
            .flatMap { kubeConfig ->
                val clusters = KubeConfigNamedCluster.fromKubeConfig(kubeConfig)
                clusters.map { clusterEntry ->
                    val cluster = toCluster(clusterEntry, kubeConfig)
                    logger.info("Parsed cluster: ${cluster.name} at ${cluster.apiServerUrl}")
                    cluster
                }
            }
            .distinctBy { it.id }
            
        logger.info("Returning ${clusters.size} distinct clusters")
        return clusters
    }

    private fun toKubeConfigs(kubeconfigPaths: List<Path>): List<KubeConfig> = kubeconfigPaths
        .filter { path ->
            val valid = isValid(path)
            if (!valid) {
                logger.info("Kubeconfig file does not exist or is not a regular file: $path")
            }
            valid
        }.mapNotNull { path ->
            try {
                val kubeConfig = KubeConfig.loadKubeConfig(
                    StringReader(path.toFile().readText())
                )
                logger.info("loaded kubeconfig from: $path")
                kubeConfig
            } catch (e: Exception) {
                logger.warn("Error parsing kubeconfig file '$path': ${e.message}", e)
                null
            }
        }

    private fun toCluster(clusterEntry: KubeConfigNamedCluster, kubeConfig: KubeConfig): Cluster {
        val userToken = getUserTokenForCluster(clusterEntry.name, kubeConfig)

        return Cluster(
            id = generateClusterId(clusterEntry.name, clusterEntry.cluster.server),
            name = clusterEntry.name,
            apiServerUrl = clusterEntry.cluster.server,
            caData = clusterEntry.cluster.certificateAuthorityData,
            token = userToken
        )
    }

    private fun getUserTokenForCluster(clusterName: String, kubeConfig: KubeConfig): String? {
        return KubeConfigNamedUser.findUserTokenForCluster(clusterName, kubeConfig)
    }

    private fun generateClusterId(clusterName: String, apiServerUrl: String): String {
        return "$clusterName@${
            apiServerUrl
                .removePrefix("https://")
                .removePrefix("http://")
        }"
    }
 
    private fun getEnvConfigs(): List<Path> {
        return System.getenv("KUBECONFIG")
            ?.split(File.pathSeparator)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.map { Path(it) }
            ?: emptyList()
    }

    private fun getDefaultConfigs(): List<Path> {
        return listOfNotNull(
            Path(System.getProperty("user.home"), ".kube", "config")
                .takeIf { isValid(it) }
        )
    }
    
    fun getAllConfigs(): List<Path> {
        val envPaths = getEnvConfigs()
        return if (envPaths.isNotEmpty()) {
            envPaths.filter { isValid(it) }
        } else {
            getDefaultConfigs()
        }
    }

    private fun isValid(paths: Path): Boolean {
        return paths.exists()
                && paths.isRegularFile()
    }

    private fun mergeConfigs(kubeconfigs: List<Path>): String {
        return kubeconfigs
            .mapNotNull { path ->
                try {
                    path.toFile().readText()
                } catch (e: Exception) {
                    logger.warn("Failed to read kubeconfig file '$path': ${e.message}")
                    null
                }
            }
            .joinToString("\n---\n")
    }

    fun getMergedConfig(): String? {
        val kubeConfigPaths = getAllConfigs()

        if (kubeConfigPaths.isEmpty()) {
            logger.debug("No kubeconfig files found.")
            return null
        }

        val mergedKubeConfigs = mergeConfigs(kubeConfigPaths)
        if (mergedKubeConfigs.isEmpty()) {
            logger.debug("No valid kubeconfig content found.")
            return null
        }

        return mergedKubeConfigs
    }
}