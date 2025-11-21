package com.redhat.devtools.gateway.kubeconfig

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.EnvironmentUtil
import com.redhat.devtools.gateway.openshift.Cluster
import io.kubernetes.client.util.KubeConfig
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

object KubeConfigUtils {

    private val logger = thisLogger<KubeConfigUtils>()

    fun isCurrentUserTokenAuth(kubeConfig: KubeConfig): Boolean {
        return kubeConfig.credentials.containsKey(KubeConfig.CRED_TOKEN_KEY)
    }

    fun getClusters(kubeconfigPaths: List<Path>): List<Cluster> {
        logger.info("Getting clusters from kubeconfig paths: $kubeconfigPaths")
        val kubeConfigs = toKubeConfigs(kubeconfigPaths)
        logger.info("Loaded ${kubeConfigs.size} kubeconfig files from paths: $kubeconfigPaths")

        val clusters = kubeConfigs
            .flatMap { kubeConfig ->
                val namedClusters = KubeConfigNamedCluster.fromKubeConfig(kubeConfig)
                logger.info("kubeConfig.clusters size for this config: ${kubeConfig.clusters?.size}")
                namedClusters.map { clusterEntry ->
                    val cluster = toCluster(clusterEntry, kubeConfig)
                    logger.info("Parsed cluster: ${cluster.name} at ${cluster.url}")
                    cluster
                }
            }
            .distinctBy { it.id }

        logger.info("Found ${clusters.size} distinct clusters")
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
                val kubeConfig = KubeConfig.loadKubeConfig(path.toFile().bufferedReader())
                logger.info("loaded kubeconfig from: $path")
                kubeConfig
            } catch (e: Exception) {
                logger.warn("Error parsing kubeconfig file '$path': ${e.message}", e)
                null
            }
        }

    private fun toCluster(clusterEntry: KubeConfigNamedCluster, kubeConfig: KubeConfig): Cluster {
        val userToken = KubeConfigNamedUser.getUserTokenForCluster(clusterEntry.name, kubeConfig)

        return Cluster(
            url = clusterEntry.cluster.server,
            name = clusterEntry.name,
            token = userToken
        )
    }

    private fun getEnvConfigs(): List<Path> {
        val env = System.getenv("KUBECONFIG")
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

    fun getAllConfigsMerged(): String? {
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

    fun getCurrentUser(kubeConfig: KubeConfig): String {
        try {
            val currentContextName = kubeConfig.currentContext
            val currentContext = (kubeConfig.contexts as? List<*>)?.find {
                (it as? Map<*, *>)?.get("name") == currentContextName
            }
            val contextMap = currentContext as? Map<*, *>
            return (contextMap?.get("context") as? Map<*, *>)?.get("user") as? String ?: ""
        } catch (e: Exception) {
            logger.warn("Failed to get current user from kubeconfig: ${e.message}", e)
            return ""
        }
    }

    fun getCurrentContext(kubeConfig: KubeConfig): KubeConfigNamedContext? {
        val currentContextName = kubeConfig.currentContext
        return (kubeConfig.contexts as? List<*>)
            ?.mapNotNull { contextObject ->
                val contextMap = contextObject as? Map<*, *> ?: return@mapNotNull null
                val name = contextMap["name"] as? String ?: return@mapNotNull null
                val contextDetails = contextMap["context"] as? Map<*, *> ?: return@mapNotNull null
                if (name == currentContextName) {
                    KubeConfigNamedContext(
                        name = name,
                        context = KubeConfigContext.fromMap(contextDetails) ?: return@mapNotNull null
                    )
                } else {
                    null
                }
            }?.firstOrNull()
    }

    fun getCurrentContextClusterName(): String? {
        return try {
            getAllConfigs().firstNotNullOfOrNull { path ->
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
                    } catch (e: Exception) {
                        logger.warn("Error parsing kubeconfig file '$path' while determining current context: ${e.message}", e)
                        null
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to get current context cluster name from kubeconfig: ${e.message}", e)
            null
        }
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

}