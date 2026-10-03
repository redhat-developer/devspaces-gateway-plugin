/*
 * Copyright (c) 2025-2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.kubeconfig

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.EnvironmentUtil
import com.redhat.devtools.gateway.openshift.Cluster
import com.redhat.devtools.gateway.util.toServerBaseUrl
import io.kubernetes.client.util.KubeConfig
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.Locale.getDefault
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

object KubeConfigUtils {

    private val logger = thisLogger<KubeConfigUtils>()

    fun isCurrentUserTokenAuth(kubeConfig: KubeConfig): Boolean {
        val currentContext = getCurrentContext(kubeConfig) ?: return false
        val currentUser = KubeConfigNamedUser.getByName(currentContext.context.user, kubeConfig)
        return currentUser?.user?.token != null
    }

    fun getClusterByServer(
        serverUrl: String,
        kubeConfigs: List<KubeConfig>
    ): KubeConfigNamedCluster? {
        val baseUrl = runCatching { URI(serverUrl).toServerBaseUrl() }.getOrNull()
        return kubeConfigs
            .flatMap { it.clusters ?: emptyList() }
            .mapNotNull { KubeConfigNamedCluster.fromMap(it as Map<*, *>) }
            .firstOrNull { cluster ->
                cluster.cluster.server == serverUrl ||
                    (baseUrl != null && cluster.cluster.server == baseUrl)
            }
    }

    fun getClusters(kubeconfigPaths: List<Path>): List<Cluster> {
        logger.info("Getting clusters from kubeconfig paths: $kubeconfigPaths")
        val kubeConfigs = toKubeConfigs(kubeconfigPaths)
        logger.info("Loaded ${kubeConfigs.size} kubeconfig files from paths: $kubeconfigPaths")

        val clusters = kubeConfigs
            .flatMap { kubeConfigFile ->
                val kubeConfig = kubeConfigFile.config
                kubeConfig.clusters?.mapNotNull { cluster ->
                    val namedCluster = KubeConfigNamedCluster.fromMap(cluster as Map<*, *>) ?: return@mapNotNull null
                    val kubeUser = KubeConfigNamedUser.getUserForCluster(namedCluster.name, kubeConfig)
                    val clusterModel = toCluster(namedCluster, kubeUser)
                    logger.debug("Parsed cluster: ${clusterModel.name} at ${clusterModel.url}")
                    clusterModel
                } ?: emptyList()
            }
            .distinctBy { it.id }

        logger.info("Found ${clusters.size} distinct clusters")
        return clusters
    }

    private fun toKubeConfigs(kubeconfigPaths: List<Path>): List<KubeConfigFile> {
        return kubeconfigPaths
            .filter { path ->
                val valid = isValid(path)
                if (!valid) {
                    logger.info("Kubeconfig file does not exist or is not a regular file: $path")
                }
                valid
            }.mapNotNull { path ->
                try {
                    val content = path.toFile().readText()
                    if (content.isBlank()) {
                        logger.info("Kubeconfig file is empty: $path")
                        return@mapNotNull null
                    }

                    val kubeConfig = KubeConfig.loadKubeConfig(content.reader())
                    logger.info("loaded kubeconfig from: $path")
                    kubeConfig.setFile(path.toFile())
                    KubeConfigFile(kubeConfig, path)
                } catch (t: Throwable) {
                    logger.debug("Error loading kubeconfig file '$path': ${t.message}", t)
                    null
                }
            }
    }

    private fun toCluster(
        clusterEntry: KubeConfigNamedCluster,
        kubeUser: KubeConfigUser?
    ): Cluster {
        return Cluster(
            url = clusterEntry.cluster.server,
            name = clusterEntry.name,
            certificateAuthority = clusterEntry.cluster.certificateAuthority,
            token = kubeUser?.token,
            clientCert = kubeUser?.clientCertificate,
            clientKey = kubeUser?.clientKey,
            basicUsername = kubeUser?.username,
            basicPassword = kubeUser?.password,
        )
    }

    private fun getEnvConfigs(kubeconfigEnv: String? = null): List<Path> {
        val env = kubeconfigEnv
            ?: System.getenv("KUBECONFIG")
            ?: EnvironmentUtil.getValue("KUBECONFIG")
            ?: return emptyList()
        return env
            .split(File.pathSeparator)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { Path(it) }
    }

    fun getDefaultConfig(): Path =
        Path(System.getProperty("user.home"), ".kube", "config")

    fun getWritableConfig(kubeconfigEnv: String? = null): Path {
        val envPaths = getEnvConfigs(kubeconfigEnv)
        return if (envPaths.isNotEmpty()) envPaths.first() else getDefaultConfig()
    }

    fun newEmptyConfig(path: Path): KubeConfigFile {
        val config = KubeConfig(ArrayList(), ArrayList(), ArrayList())
        config.setFile(path.toFile())
        return KubeConfigFile(config, path)
    }

    fun getAllConfigFiles(kubeconfigEnv: String? = null): List<Path> {
        val envPaths = getEnvConfigs(kubeconfigEnv)
        return if (envPaths.isNotEmpty()) {
            envPaths.filter { isValid(it) }
        } else {
            listOf(getDefaultConfig())
        }
    }

    private fun isValid(paths: Path): Boolean {
        return paths.exists()
                && paths.isRegularFile()
    }

    fun getAllConfigs(files: List<Path>): List<KubeConfigFile> {
        return files.mapNotNull { file ->
            try {
                val document = file.toFile().readText()
                val kubeConfig = KubeConfig.loadKubeConfig(document.reader()) ?: return@mapNotNull null
                kubeConfig.setFile(file.toFile())
                KubeConfigFile(kubeConfig, file)
            } catch (e: Throwable) {
                logger.debug("Could not parse kubeconfig document", e)
                null
            }
        }
    }

    fun getCurrentContext(kubeConfig: KubeConfig): KubeConfigNamedContext? {
        val currentContextName = kubeConfig.currentContext
        return (kubeConfig.contexts as? List<*>)
            ?.mapNotNull { contextObject ->
                val context = KubeConfigNamedContext.fromMap(contextObject as? Map<*,*>) ?: return@mapNotNull null
                if (context.name == currentContextName) {
                    context
                } else {
                    null
                }
            }?.firstOrNull()
    }

    fun getCurrentClusterName(kubeconfigEnv: String? = null): String? {
        return try {
            val allKubeConfigs = getAllConfigFiles(kubeconfigEnv)
                .filter { isValid(it) }
                .mapNotNull { path ->
                    try {
                        KubeConfig.loadKubeConfig(path.toFile().bufferedReader())
                    } catch (e: Throwable) {
                        logger.warn(
                            "Error parsing kubeconfig file '$path' while determining current context: ${e.message}",
                            e
                        )
                        null
                    }
                }
            
            val currentContextName = allKubeConfigs
                .firstOrNull { it.currentContext?.isNotEmpty() == true }
                ?.currentContext
                ?: return null
            
            allKubeConfigs
                .flatMap { it.contexts ?: emptyList() }
                .mapNotNull { KubeConfigNamedContext.fromMap(it as? Map<*, *>) }
                .firstOrNull { it.name == currentContextName }
                ?.context?.cluster
        } catch (e: Exception) {
            logger.warn("Failed to get current context cluster name from kubeconfig: ${e.message}", e)
            null
        }
    }

    fun getConfigByUser(context: KubeConfigNamedContext, allConfigs: List<KubeConfigFile>): KubeConfigFile? {
        val contextUser = context.context.user
        return getConfigByUser(contextUser, allConfigs)
    }

    private fun getConfigByUser(userName: String, allConfigs: List<KubeConfigFile>): KubeConfigFile? {
        return allConfigs
            .firstOrNull { configFile ->
                KubeConfigNamedUser.getByName(userName, configFile.config) != null
            }
    }

    fun getConfigWithCurrentContext(allConfigs: List<KubeConfigFile>): KubeConfigFile? {
        return allConfigs
            .firstOrNull { configFile ->
                !configFile.config.currentContext.isNullOrBlank()
            }
    }

    fun toUriWithHost(url: String?): URI? {
        return if (url.isNullOrBlank()) {
            null
        } else {
            try {
                val uri = URI.create(url)
                if (uri?.host == null) {
                    null
                } else {
                    uri
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun toName(uri: URI?): String? {
        return if (uri == null) {
            null
        } else if (uri.host == null) {
            null
        } else {
            "${uri.host}${
                if (uri.port != -1) {
                    "-${uri.port}"
                } else ""
            }"
        }
    }

    fun urlToName(url: String?): String? {
        return if (url?.isEmpty() == true) {
            null
        } else {
            toName(toUriWithHost(url))
        }
    }

    fun sanitizeName(name: String): String {
        // allowed: only alphanumeric, hyphen, period, max 253 chars
        return name
            .lowercase(getDefault())
            .replace(Regex("[^a-z0-9-.]"), "-")
            .replace(Regex("^(-+)(\\.)*|(-+)(\\.)*$"), "")
            .take(253)
    }

    fun mergeConfigs(configs: List<KubeConfig>): KubeConfig {
        if (configs.size == 1) {
            return configs.first()
        }

        val allClusters = ArrayList(configs.flatMap { it.clusters ?: emptyList() }
            .distinctBy { (it as? Map<*, *>)?.get("name") })
        val allUsers = ArrayList(configs.flatMap { it.users ?: emptyList() }
            .distinctBy { (it as? Map<*, *>)?.get("name") })
        val allContexts = ArrayList(configs.flatMap { it.contexts ?: emptyList() }
            .distinctBy { (it as? Map<*, *>)?.get("name") })

        val currentContext = configs.firstNotNullOfOrNull { config ->
            config.currentContext?.takeIf { it.isNotBlank() }
        }

        val mergedConfig = KubeConfig(allContexts, allClusters, allUsers)
        currentContext?.let { mergedConfig.setContext(it) }

        return mergedConfig
    }
}
