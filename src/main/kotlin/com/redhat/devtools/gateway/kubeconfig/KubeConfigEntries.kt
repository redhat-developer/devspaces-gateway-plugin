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

import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils.sanitizeName
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils.urlToName
import io.kubernetes.client.util.KubeConfig


/**
 * Domain classes representing the structure of a kubeconfig file.
 */
data class KubeConfigNamedCluster(
    val cluster: KubeConfigCluster,
    val name: String = toName(cluster)
) {

    companion object {
        fun fromMap(map: Map<*,*>): KubeConfigNamedCluster? {
            val name = map["name"] as? String ?: return null
            val clusterDetails = map["cluster"] as? Map<*, *> ?: return null

            return KubeConfigNamedCluster(
                name = name,
                cluster = KubeConfigCluster.fromMap(clusterDetails) ?: return null
            )
        }

        private fun toName(cluster: KubeConfigCluster): String {
            val url = cluster.server
            return urlToName(url) ?: url
        }

    }

    fun toMap(): MutableMap<String, Any> {
        return mutableMapOf(
            "name" to name,
            "cluster" to cluster.toMap()
        )
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

    fun toMap(): MutableMap<String, Any> {
        val map = mutableMapOf<String, Any>()
        map["server"] = server
        certificateAuthorityData?.let { map["certificate-authority-data"] = it }
        insecureSkipTlsVerify?.let { map["insecure-skip-tls-verify"] = it }
        return map
    }
}

data class KubeConfigNamedContext(
    val context: KubeConfigContext,
    val name: String = toName(context.user, context.cluster)
) {
    companion object {

        private fun toName(user: String, cluster: String): String {
            val sanitizedUser = sanitizeName(user)
            val sanitizedCluster = sanitizeName(cluster)

            return when {
                sanitizedUser.isEmpty() && sanitizedCluster.isEmpty() -> ""
                sanitizedUser.isEmpty() -> sanitizedCluster
                sanitizedCluster.isEmpty() -> sanitizedUser
                else -> "$sanitizedUser/$sanitizedCluster"
            }
        }

        fun getByClusterName(name: String?, allConfigs: List<KubeConfig>): KubeConfigNamedContext? {
            return allConfigs
                .flatMap {
                    it.contexts ?: emptyList()
                }
                .mapNotNull {
                    fromMap(it as? Map<*,*>)
                }
                .firstOrNull { context ->
                    name == context.context.cluster
                }
        }

        fun getByName(clusterName: String, kubeConfig: KubeConfig): KubeConfigNamedContext? {
            return (kubeConfig.contexts as? List<*>)
                ?.firstNotNullOfOrNull { contextObject ->
                    val contextEntry = fromMap(contextObject as? Map<*, *>)
                    if (contextEntry?.context?.cluster == clusterName) {
                        contextEntry
                    } else {
                        null
                    }
            }
        }

        fun fromMap(map: Map<*, *>?): KubeConfigNamedContext? {
            if (map == null) return null
            val name = map["name"] as? String ?: return null
            val context = map["context"] as? Map<*, *> ?: return null
            return KubeConfigNamedContext(
                name = name,
                context = KubeConfigContext.fromMap(context) ?: return null
            )
        }
    }

    fun toMap(): MutableMap<String, Any> {
        return mutableMapOf(
            "name" to name,
            "context" to context.toMap()
        )
    }
}

data class KubeConfigContext(
    val user: String,
    val cluster: String,
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

    fun toMap(): MutableMap<String, Any> {
        val map = mutableMapOf<String, Any>()
        map["cluster"] = cluster
        map["user"] = user
        namespace?.let { map["namespace"] = it }
        return map
    }
}

data class KubeConfigNamedUser(
    val user: KubeConfigUser?,
    val name: String
) {
    companion object {

        fun getByName(userName: String, config: KubeConfig?): KubeConfigNamedUser? {
            return (config?.users ?: emptyList<Any>())
                .mapNotNull {
                    it as? Map<String, Any>
                }
                .firstOrNull { user ->
                    userName == user["name"]
                }
                ?.let { fromMap(it) }
        }

        fun fromMap(map: Map<*,*>): KubeConfigNamedUser? {
            val name = map["name"] as? String ?: return null
            val userDetails = map["user"] as? Map<*, *> ?: return null
            val user = KubeConfigUser.fromMap(userDetails)
            return KubeConfigNamedUser(
                name = name,
                user = user
            )
        }

        fun getUserTokenForCluster(clusterName: String, kubeConfig: KubeConfig): String? {
            val contextEntry = KubeConfigNamedContext.getByName(clusterName, kubeConfig) ?: return null
            val userObject = (kubeConfig.users as? List<*>)?.firstOrNull { userObject ->
                val userMap = userObject as? Map<*, *> ?: return@firstOrNull false
                val userName = userMap["name"] as? String ?: return@firstOrNull false
                userName == contextEntry.context.user
            } as? Map<*,*> ?: return null
            return fromMap(userObject)?.user?.token
        }

        fun isTokenAuth(kubeConfig: KubeConfig): Boolean {
            return kubeConfig.credentials?.containsKey(KubeConfig.CRED_TOKEN_KEY) == true
        }
    }

    fun toMap(): MutableMap<String, Any> {
        return mutableMapOf(
            "name" to name,
            "user" to (user?.toMap() ?: mutableMapOf())
        )
    }
}

data class KubeConfigUser(
    var token: String? = null,
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

    fun toMap(): MutableMap<String, Any> {
        val map = mutableMapOf<String, Any>()
        token?.let { map["token"] = it }
        clientCertificateData?.let { map["client-certificate-data"] = it }
        clientKeyData?.let { map["client-key-data"] = it }
        username?.let { map["username"] = it }
        password?.let { map["password"] = it }
        return map
    }
}



