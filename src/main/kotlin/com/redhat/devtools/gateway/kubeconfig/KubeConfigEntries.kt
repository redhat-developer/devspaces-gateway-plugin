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

import io.kubernetes.client.util.KubeConfig
import kotlin.collections.get

/**
 * Domain classes representing the structure of a kubeconfig file.
 */
data class KubeConfigNamedCluster(
    val name: String,
    val cluster: KubeConfigCluster
) {
    companion object {
        fun fromMap(name: String, clusterObject: Any?): KubeConfigNamedCluster? {
            val clusterMap = clusterObject as? Map<*, *> ?: return null
            val clusterDetails = clusterMap["cluster"] as? Map<*, *> ?: return null
            
            return KubeConfigNamedCluster(
                name = name,
                cluster = KubeConfigCluster.fromMap(clusterDetails) ?: return null
            )
        }
        
        fun fromKubeConfig(kubeConfig: KubeConfig): List<KubeConfigNamedCluster> {
            return (kubeConfig.clusters as? List<*>)
                ?.mapNotNull { clusterObject ->
                    val clusterMap = clusterObject as? Map<*, *> ?: return@mapNotNull null
                    val name = clusterMap["name"] as? String ?: return@mapNotNull null
                    fromMap(name, clusterObject)
                } ?: emptyList()
        }
    }
}

data class KubeConfigCluster(
    val server: String,
    val certificateAuthorityData: String? = null,
    val insecureSkipTlsVerify: Boolean? = null
) {
    companion object {
        fun fromMap(map: Map<*, *>): KubeConfigCluster? {
            val server = map["server"] as? String ?: return null
            return KubeConfigCluster(
                server = server,
                certificateAuthorityData = map["certificate-authority-data"] as? String,
                insecureSkipTlsVerify = map["insecure-skip-tls-verify"] as? Boolean
            )
        }
    }
}

data class KubeConfigNamedContext(
    val name: String,
    val context: KubeConfigContext
) {
    companion object {
        fun getByClusterName(clusterName: String, kubeConfig: KubeConfig): KubeConfigNamedContext? {
            return (kubeConfig.contexts as? List<*>)?.firstNotNullOfOrNull { contextObject ->
                val contextMap = contextObject as? Map<*, *> ?: return@firstNotNullOfOrNull null
                val contextName = contextMap["name"] as? String ?: return@firstNotNullOfOrNull null
                val contextEntry = getByName(contextName, contextObject)
                if (contextEntry?.context?.cluster == clusterName) {
                    contextEntry
                } else {
                    null
                }
            }
        }

        private fun getByName(name: String, contextObject: Any?): KubeConfigNamedContext? {
            val contextMap = contextObject as? Map<*, *> ?: return null
            val contextDetails = contextMap["context"] as? Map<*, *> ?: return null

            return KubeConfigNamedContext(
                name = name,
                context = KubeConfigContext.fromMap(contextDetails) ?: return null
            )
        }
    }
}

data class KubeConfigContext(
    val cluster: String,
    val user: String,
    val namespace: String? = null
) {
    companion object {
        fun fromMap(map: Map<*, *>): KubeConfigContext? {
            val cluster = map["cluster"] as? String ?: return null
            val user = map["user"] as? String ?: return null
            
            return KubeConfigContext(
                cluster = cluster,
                user = user,
                namespace = map["namespace"] as? String
            )
        }
    }
}

data class KubeConfigNamedUser(
    val name: String,
    val user: KubeConfigUser
) {
    companion object {
        fun fromMap(name: String, userObject: Any?): KubeConfigNamedUser? {
            val userMap = userObject as? Map<*, *> ?: return null
            val userDetails = userMap["user"] as? Map<*, *> ?: return null
            
            return KubeConfigNamedUser(
                name = name,
                user = KubeConfigUser.fromMap(userDetails)
            )
        }
        
        fun getUserTokenForCluster(clusterName: String, kubeConfig: KubeConfig): String? {
            val contextEntry = KubeConfigNamedContext.getByClusterName(clusterName, kubeConfig) ?: return null
            val userObject = (kubeConfig.users as? List<*>)?.firstOrNull { userObject ->
                val userMap = userObject as? Map<*, *> ?: return@firstOrNull false
                val userName = userMap["name"] as? String ?: return@firstOrNull false
                userName == contextEntry.context.user
            } ?: return null
            return fromMap(contextEntry.context.user, userObject)?.user?.token
        }

        fun isTokenAuth(kubeConfig: KubeConfig): Boolean {
            return kubeConfig.credentials?.containsKey(KubeConfig.CRED_TOKEN_KEY) == true
        }
    }
}

data class KubeConfigUser(
    val token: String? = null,
    val clientCertificateData: String? = null,
    val clientKeyData: String? = null,
    val username: String? = null,
    val password: String? = null
) {
    companion object {
        fun fromMap(map: Map<*, *>): KubeConfigUser {
            return KubeConfigUser(
                token = map["token"] as? String,
                clientCertificateData = map["client-certificate-data"] as? String,
                clientKeyData = map["client-key-data"] as? String,
                username = map["username"] as? String,
                password = map["password"] as? String
            )
        }
    }
}

