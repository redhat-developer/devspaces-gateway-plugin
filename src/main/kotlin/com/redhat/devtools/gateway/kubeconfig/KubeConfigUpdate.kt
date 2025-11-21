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
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils.path
import com.redhat.devtools.gateway.openshift.Utils
import io.kubernetes.client.util.KubeConfig
import java.nio.file.Path

abstract class KubeConfigUpdate private constructor(
    protected val clusterName: String,
    protected val clusterUrl: String,
    protected val token: String,
    protected val allConfigs: List<KubeConfig>
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
    }

    abstract fun apply()

    protected fun save(
        contexts: ArrayList<Any?>,
        clusters: ArrayList<Any?>,
        users: ArrayList<Any?>,
        preferences: Any,
        currentContext: String?,
        path: Path?
    ) {
        val file = path?.toFile() ?: run {
            thisLogger().info("Could not write kubeconfig file. Path missing.")
            return
        }
        val persister = BlockStyleFilePersister(file)
        persister.save(
            contexts,
            clusters,
            users,
            preferences,
            currentContext
        )
    }

    class UpdateToken(
        clusterName: String,
        clusterUrl: String,
        token: String,
        private val context: KubeConfigNamedContext,
        allConfigs: List<KubeConfig>
    ) : KubeConfigUpdate(clusterName, clusterUrl, token, allConfigs) {

        override fun apply() {
            updateToken(context.context.user)
            updateCurrentContext(context.name)
        }

        private fun updateToken(username: String) {
            val config = KubeConfigUtils.getConfigByUser(context, allConfigs) ?: return
            config.users?.find { user ->
                username == Utils.getValue(user, arrayOf("name"))
            }?.apply {
                Utils.setValue(this, token, arrayOf("user", "token"))
            }

            save(
                config.contexts,
                config.clusters,
                config.users,
                config.preferences,
                config.currentContext,
                config.path
            )
        }

        private fun updateCurrentContext(contextName: String) {
            val config = KubeConfigUtils.getConfigWithCurrentContext(allConfigs) ?: return
            save(
                config.contexts,
                config.clusters,
                config.users,
                config.preferences,
                contextName,
                config.path
            )
        }
    }

    class CreateContext(
        clusterName: String,
        clusterUrl: String,
        token: String,
        allConfigs: List<KubeConfig>
    ) : KubeConfigUpdate(clusterName, clusterUrl, token, allConfigs) {
        override fun apply() {
            // create new context in first config
            val config = allConfigs.firstOrNull() ?: return

            val user = createUser(allConfigs)
            val users = config.users ?: ArrayList()
            users.add(user.toMap())

            val cluster = createCluster(allConfigs)
            val clusters = config.clusters ?: ArrayList()
            clusters.add(cluster.toMap())

            val context = createContext(user, cluster, allConfigs)
            val contexts = config.contexts ?: ArrayList()
            contexts.add(context.toMap())

            config.setContext(context.name)

            save(
                contexts,
                clusters,
                users,
                config.preferences,
                config.currentContext,
                config.path
            )
        }

        private fun createUser(allConfigs: List<KubeConfig>): KubeConfigNamedUser {
            val existingUserNames = getAllExistingNames(allConfigs) { it.users }
            val uniqueUserName = UniqueNameGenerator.generateUniqueName(clusterName, existingUserNames)
            return KubeConfigNamedUser(
                KubeConfigUser(token),
                uniqueUserName
            )
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
            val tempContext = KubeConfigNamedContext(
                KubeConfigContext(
                    user.name,
                    cluster.name
                )
            )
            val uniqueContextName = UniqueNameGenerator.generateUniqueName(tempContext.name, existingContextNames)

            return KubeConfigNamedContext(
                KubeConfigContext(
                    user.name,
                    cluster.name
                ),
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
    }

}
