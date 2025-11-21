package com.redhat.devtools.gateway.kubeconfig

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.EnvironmentUtil
import com.redhat.devtools.gateway.openshift.Cluster
import com.redhat.devtools.gateway.openshift.Utils
import com.sun.jndi.toolkit.url.Uri
import io.kubernetes.client.util.KubeConfig
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.*
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

    fun getClusters(kubeconfigPaths: List<Path>): List<Cluster> {
        logger.info("Getting clusters from kubeconfig paths: $kubeconfigPaths")
        val kubeConfigs = toKubeConfigs(kubeconfigPaths)
        logger.info("Loaded ${kubeConfigs.size} kubeconfig files from paths: $kubeconfigPaths")

        val clusters = kubeConfigs
            .flatMap { kubeConfig ->
                kubeConfig.clusters?.mapNotNull { cluster ->
                    val namedCluster = KubeConfigNamedCluster.fromMap(cluster as Map<*, *>) ?: return@mapNotNull null
                    val token = KubeConfigNamedUser.getUserTokenForCluster(namedCluster.name, kubeConfig)
                    val cluster = toCluster(namedCluster, token)
                    logger.debug("Parsed cluster: ${cluster.name} at ${cluster.url}")
                    cluster
                } ?: emptyList()
            }
            .distinctBy { it.id }

        logger.info("Found ${clusters.size} distinct clusters")
        return clusters
    }

    private fun toKubeConfigs(kubeconfigPaths: List<Path>): List<KubeConfig> {
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
                    kubeConfig
                } catch (t: Throwable) {
                    logger.debug("Error loading kubeconfig file '$path': ${t.message}", t)
                    null
                }
            }
    }

    private fun toCluster(clusterEntry: KubeConfigNamedCluster, userToken: String?): Cluster {
        return Cluster(
            url = clusterEntry.cluster.server,
            name = clusterEntry.name,
            token = userToken
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

    private fun getDefaultConfigs(): List<Path> {
        return listOfNotNull(
            Path(System.getProperty("user.home"), ".kube", "config")
                .takeIf { isValid(it) }
        )
    }

    fun getAllConfigFiles(kubeconfigEnv: String? = null): List<Path> {
        val envPaths = getEnvConfigs(kubeconfigEnv)
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

    private fun getDocuments(file: Path): List<String> {
        return try {
            file.toFile().readText().split("---")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            thisLogger().info("Could not read config file: $file", e)
            emptyList()
        }
    }

    private fun getAllDocuments(files: List<Path>): List<String> {
        return files.flatMap { getDocuments(it) }
    }

    fun toString(files: List<Path>): String? {
        if (files.isEmpty()) {
            logger.debug("No kubeconfig files found.")
            return null
        }
        val mergedKubeConfigs = getAllDocuments(files).joinToString("\n---\n")
        if (mergedKubeConfigs.isEmpty()) {
            logger.debug("No valid kubeconfig content found.")
            return null
        }
        return mergedKubeConfigs
    }

    fun getAllConfigs(files: List<Path>): List<KubeConfig> {
        return files.flatMap { file ->
            getDocuments(file).mapNotNull { document ->
                try {
                    val kubeConfig = KubeConfig.loadKubeConfig(document.reader())
                    kubeConfig?.let {
                        kubeConfig.path = file
                    }
                    kubeConfig
                } catch (e: Throwable) {
                    logger.debug("Could not parse kubeconfig document", e)
                    null
                }
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
            getAllConfigFiles(kubeconfigEnv).firstNotNullOfOrNull { path ->
                if (!isValid(path)) {
                    null
                } else {
                    try {
                        val kubeConfig = KubeConfig.loadKubeConfig(path.toFile().bufferedReader())
                        if (!kubeConfig.currentContext.isNullOrBlank()) {
                            getCurrentContext(kubeConfig)?.context?.cluster
                        } else {
                            null
                        }
                    } catch (e: Throwable) {
                        logger.warn(
                            "Error parsing kubeconfig file '$path' while determining current context: ${e.message}",
                            e
                        )
                        null
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to get current context cluster name from kubeconfig: ${e.message}", e)
            null
        }
    }

    fun getConfigByUser(context: KubeConfigNamedContext, allConfigs: List<KubeConfig>): KubeConfig? {
        val contextUser = context.context.user
        return getConfigByUser(contextUser, allConfigs)
    }

    private fun getConfigByUser(userName: String, allConfigs: List<KubeConfig>): KubeConfig? {
        return allConfigs
            .firstOrNull { config ->
                KubeConfigNamedUser.getByName(userName, config) != null
            }
    }

    fun getConfigWithCurrentContext(allConfigs: List<KubeConfig>): KubeConfig? {
        return allConfigs
            .firstOrNull { config ->
                !config.currentContext.isNullOrBlank()
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

    private val kubeConfigFiles = WeakHashMap<KubeConfig, Path>()

    var KubeConfig.path: Path?
        get() = kubeConfigFiles[this]
        set(value) {
            if (value != null) {
                kubeConfigFiles[this] = value
                this.setFile(value.toFile())
            } else {
                kubeConfigFiles.remove(this)
            }
        }
}