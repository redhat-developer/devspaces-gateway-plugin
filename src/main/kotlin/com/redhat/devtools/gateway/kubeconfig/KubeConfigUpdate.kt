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
package com.redhat.devtools.gateway.kubeconfig

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.text.UniqueNameGenerator
import com.redhat.devtools.gateway.auth.tls.CertificateSource
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils.path
import com.redhat.devtools.gateway.openshift.Utils
import io.kubernetes.client.persister.ConfigPersister
import io.kubernetes.client.util.KubeConfig
import java.io.File
import java.nio.file.Path

abstract class KubeConfigUpdate private constructor(
    protected val clusterName: String,
    protected val clusterUrl: String,
    protected val token: String,
    protected val allConfigs: List<KubeConfig>,
    private val persisterFactory: (File) -> ConfigPersister,
) {

    companion object {
        fun create(clusterName: String, clusterUrl: String, token: String): KubeConfigUpdate {
            val allConfigs = KubeConfigUtils.getAllConfigs(KubeConfigUtils.getAllConfigFiles())
            val context = KubeConfigNamedContext.getByClusterName(clusterName, allConfigs)
            return if (context == null) {
                CreateContext(clusterName, clusterUrl, token, allConfigs)
            } else {
                UpdateToken(clusterName, clusterUrl, token, context, allConfigs)
            }
        }

        fun create(clusterName: String, clusterUrl: String, clientCertPem: String, clientKeyPem: String): KubeConfigUpdate {
            val allConfigs = KubeConfigUtils.getAllConfigs(KubeConfigUtils.getAllConfigFiles())
            val context = KubeConfigNamedContext.getByClusterName(clusterName, allConfigs)
            return if (context == null) {
                CreateContextWithClientCert(clusterName, clusterUrl, clientCertPem, clientKeyPem, allConfigs)
            } else {
                UpdateClientCert(clusterName, clusterUrl, clientCertPem, clientKeyPem, context, allConfigs)
            }
        }

    }

    abstract fun apply()

    protected fun saveConfigs(
        primaryConfig: KubeConfig,
        currentContextConfig: KubeConfig?,
        currentContextName: String?
    ) {
        when {
            currentContextConfig == null ->
                saveConfig(primaryConfig)
            primaryConfig.path == currentContextConfig.path ->
                saveConfig(primaryConfig, currentContextName ?: primaryConfig.currentContext)
            else -> {
                saveConfig(primaryConfig)
                saveConfig(currentContextConfig, currentContextName ?: currentContextConfig.currentContext)
            }
        }
    }

    protected fun saveConfig(config: KubeConfig, currentContext: String? = config.currentContext) {
        saveConfig(
            config.contexts,
            config.clusters,
            config.users,
            config.preferences,
            currentContext,
            config.path
        )
    }

    protected fun saveConfig(
        config: KubeConfig,
        users: ArrayList<Any?>,
        clusters: ArrayList<Any?>,
        contexts: ArrayList<Any?>,
        currentContext: String
    ) {
        saveConfig(contexts, clusters, users, config.preferences, currentContext, config.path)
    }

    private fun saveConfig(
        contexts: ArrayList<Any?>?,
        clusters: ArrayList<Any?>?,
        users: ArrayList<Any?>?,
        preferences: Any?,
        currentContext: String?,
        path: Path?
    ) {
        val file = path?.toFile() ?: run {
            thisLogger().info("Could not write kubeconfig file. Path missing.")
            return
        }
        val persister = persisterFactory(file)
        persister.save(
            contexts,
            clusters,
            users,
            preferences,
            currentContext
        )
    }

    protected data class ContextEntries(
        val users: ArrayList<Any?>,
        val clusters: ArrayList<Any?>,
        val contexts: ArrayList<Any?>,
        val currentContextName: String
    )

    protected fun createContext(
        user: KubeConfigNamedUser,
        users: ArrayList<Any?>?,
        clusters: ArrayList<Any?>?,
        contexts: ArrayList<Any?>?,
    ): ContextEntries {
        val updatedUsers = (users ?: ArrayList()).apply {
            add(user.toMap())
        }

        val cluster = createCluster(allConfigs)
        val updatedClusters = (clusters ?: ArrayList()).apply {
            add(cluster.toMap())
        }

        val context = createContext(user, cluster, allConfigs)
        val updatedContexts = (contexts ?: ArrayList()).apply {
            add(context.toMap())
        }

        return ContextEntries(updatedUsers, updatedClusters, updatedContexts, context.name)
    }

    protected fun uniqueUserName(allConfigs: List<KubeConfig>): String {
        val existingUserNames = getAllExistingNames(allConfigs) { it.users }
        return UniqueNameGenerator.generateUniqueName(clusterName, existingUserNames)
    }

    private fun createCluster(allConfigs: List<KubeConfig>): KubeConfigNamedCluster {
        val existingClusterNames = getAllExistingNames(allConfigs) { it.clusters }
        val uniqueClusterName = UniqueNameGenerator.generateUniqueName(clusterName, existingClusterNames)

        return KubeConfigNamedCluster(
            KubeConfigCluster(clusterUrl),
            uniqueClusterName
        )
    }

    private fun createContext(
        user: KubeConfigNamedUser,
        cluster: KubeConfigNamedCluster,
        allConfigs: List<KubeConfig>
    ): KubeConfigNamedContext {
        val existingContextNames = getAllExistingNames(allConfigs) { it.contexts }
        val defaultContextName = KubeConfigNamedContext.toName(user.name, cluster.name)
        val uniqueContextName = UniqueNameGenerator.generateUniqueName(defaultContextName, existingContextNames)

        return KubeConfigNamedContext(
            KubeConfigContext(user.name, cluster.name),
            uniqueContextName
        )
    }

    private fun getAllExistingNames(
        allConfigs: List<KubeConfig>,
        extractList: (KubeConfig) -> List<*>?
    ): Set<String> {
        return allConfigs
            .flatMap { config -> extractList(config) ?: emptyList() }
            .mapNotNull { entryObject ->
                val entryMap = entryObject as? Map<*, *> ?: return@mapNotNull null
                entryMap["name"] as? String
            }
            .toSet()
    }

    class UpdateToken(
        clusterName: String,
        clusterUrl: String,
        token: String,
        private val context: KubeConfigNamedContext,
        allConfigs: List<KubeConfig>,
        persisterFactory: (File) -> ConfigPersister = { BlockStyleFilePersister(it) },
    ) : KubeConfigUpdate(clusterName, clusterUrl, token, allConfigs, persisterFactory) {

        override fun apply() {
            val config = KubeConfigUtils.getConfigByUser(context, allConfigs) ?: return
            setTokenFor(context.context.user, config)

            val currentContextConfig = KubeConfigUtils.getConfigWithCurrentContext(allConfigs) ?: config
            currentContextConfig.setContext(context.name)

            saveConfigs(config, currentContextConfig, context.name)
        }

        private fun setTokenFor(username: String, config: KubeConfig) {
            config.users?.find { user ->
                username == Utils.getValue(user, arrayOf("name"))
            }?.apply {
                Utils.setValue(this, token, arrayOf("user", "token"))

                removeClientCerts(this)
            }
        }

        private fun removeClientCerts(namedUser: Any) {
            Utils.removeValue(namedUser, arrayOf("user", "client-certificate-data"))
            Utils.removeValue(namedUser, arrayOf("user", "client-key-data"))
        }

    }

    class CreateContext(
        clusterName: String,
        clusterUrl: String,
        private val authToken: String,
        allConfigs: List<KubeConfig>,
        persisterFactory: (File) -> ConfigPersister = { BlockStyleFilePersister(it) },
    ) : KubeConfigUpdate(clusterName, clusterUrl, authToken, allConfigs, persisterFactory) {

        override fun apply() {
            val config = allConfigs.firstOrNull() ?: return
            val user = KubeConfigNamedUser(
                KubeConfigUser(authToken),
                uniqueUserName(allConfigs)
            )
            val entries = createContext(user, config.users, config.clusters, config.contexts)
            config.setContext(entries.currentContextName)

            saveConfig(config, entries.users, entries.clusters, entries.contexts, entries.currentContextName)
        }
    }

    class UpdateClientCert(
        clusterName: String,
        clusterUrl: String,
        private val clientCertPem: String,
        private val clientKeyPem: String,
        private val context: KubeConfigNamedContext,
        allConfigs: List<KubeConfig>,
        persisterFactory: (File) -> ConfigPersister = { BlockStyleFilePersister(it) },
    ) : KubeConfigUpdate(clusterName, clusterUrl, "", allConfigs, persisterFactory) {

        override fun apply() {
            val config = KubeConfigUtils.getConfigByUser(context, allConfigs) ?: return
            setClientCert(config, context.context.user)

            val currentContextConfig = KubeConfigUtils.getConfigWithCurrentContext(allConfigs) ?: config
            currentContextConfig.setContext(context.name)

            saveConfigs(config, currentContextConfig, context.name)
        }

        private fun setClientCert(config: KubeConfig, username: String) {
            config.users?.find { user ->
                username == Utils.getValue(user, arrayOf("name"))
            }?.apply {
                Utils.setValue(this, clientCertPem, arrayOf("user", "client-certificate-data"))
                Utils.setValue(this, clientKeyPem, arrayOf("user", "client-key-data"))

                removeToken(this)
            }
        }

        private fun removeToken(namedUser: Any) {
            Utils.removeValue(namedUser, arrayOf("user", "token"))
        }
    }

    class CreateContextWithClientCert(
        clusterName: String,
        clusterUrl: String,
        private val clientCertPem: String,
        private val clientKeyPem: String,
        allConfigs: List<KubeConfig>,
        persisterFactory: (File) -> ConfigPersister = { BlockStyleFilePersister(it) },
    ) : KubeConfigUpdate(clusterName, clusterUrl, "", allConfigs, persisterFactory) {

        override fun apply() {
            val config = allConfigs.firstOrNull() ?: return
            val user = KubeConfigNamedUser(
                KubeConfigUser(
                    token = null,
                    clientCertificate = CertificateSource.fromData(clientCertPem),
                    clientKey = CertificateSource.fromData(clientKeyPem)
                ),
                uniqueUserName(allConfigs)
            )
            val contextEntries = createContext(user, config.users, config.clusters, config.contexts)
            config.setContext(contextEntries.currentContextName)

            saveConfig(config, contextEntries.users, contextEntries.clusters, contextEntries.contexts, contextEntries.currentContextName)
        }
    }
}
