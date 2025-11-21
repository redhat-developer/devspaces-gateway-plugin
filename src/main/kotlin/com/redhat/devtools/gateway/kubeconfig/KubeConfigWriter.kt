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
import java.io.File
import java.io.FileReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml
import java.io.FileWriter

class KubeConfigWriter {
    companion object {
        /**
         * Finds the path to the kubeconfig file that contains a specific user.
         *
         * @param userName The name of the user to find.
         * @param kubeConfigEnv The value of the KUBECONFIG environment variable.
         * @return The absolute path to the kubeconfig file, or null if the user is not found.
         */
        fun findKubeConfigFileForUser(userName: String, kubeConfigEnv: String?): String? {
            val files = if (kubeConfigEnv.isNullOrEmpty()) {
                listOf(File(System.getProperty("user.home"), ".kube/config"))
            } else {
                kubeConfigEnv.split(File.pathSeparator).map { File(it) }
            }
            for (file in files) {
                if (!file.exists() || !file.isFile) {
                    continue
                }
                try {
                    val kubeConfig = KubeConfig.loadKubeConfig(FileReader(file))
                    val users = kubeConfig.users as? List<*>
                    val userFound = users?.any { userObject ->
                        val userMap = userObject as? Map<*, *>
                        userMap?.get("name") as? String == userName
                    }
                    if (userFound == true) {
                        return file.absolutePath
                    }
                } catch (e: Exception) {
                    // Log the exception, e.g., failed to parse
                }
            }
            return null
        }

        /**
         * Applies changes to a kubeconfig file and saves it.
         *
         * @param filePath The path to the kubeconfig file to modify.
         * @param serverUrl The new server URL.
         * @param token The new token.
         */
        suspend fun applyChangesAndSave(filePath: String, serverUrl: String, token: String) = withContext(Dispatchers.IO) {
            val yaml = Yaml()
            val file = File(filePath)
            val kubeConfigMap = yaml.load(file.inputStream()) as? MutableMap<String, Any> ?: mutableMapOf()

            val clusters = kubeConfigMap["clusters"] as? MutableList<MutableMap<String, Any>> ?: mutableListOf()
            val existingCluster = clusters.find { (it["cluster"] as? Map<*, *>)?.get("server") == serverUrl }

            if (existingCluster != null) {
                // Update existing
                val clusterName = existingCluster["name"] as? String
                val contexts = kubeConfigMap["contexts"] as? List<Map<String, Any>> ?: emptyList()
                val currentContext = contexts.find { it["name"] == kubeConfigMap["current-context"] }
                val userName = (currentContext?.get("context") as? Map<*, *>)?.get("user") as? String
                
                if (userName != null) {
                    val users = kubeConfigMap["users"] as? MutableList<MutableMap<String, Any>> ?: mutableListOf()
                    val userToUpdate = users.find { it["name"] == userName }
                    (userToUpdate?.get("user") as? MutableMap<String, Any>)?.set("token", token)
                }
            } else {
                // Create new
                val newName = serverUrl.substringAfter("://").replace(".", "-").replace(":", "-")
                val newClusterName = newName
                val newUserName = "$newName-user"
                val newContextName = "$newName-context"

                val newCluster = mutableMapOf<String, Any>(
                    "name" to newClusterName,
                    "cluster" to mutableMapOf("server" to serverUrl)
                )
                clusters.add(newCluster)

                val newUser = mutableMapOf<String, Any>(
                    "name" to newUserName,
                    "user" to mutableMapOf("token" to token)
                )
                val users = kubeConfigMap["users"] as? MutableList<MutableMap<String, Any>> ?: mutableListOf()
                users.add(newUser)

                val newContext = mutableMapOf<String, Any>(
                    "name" to newContextName,
                    "context" to mutableMapOf(
                        "cluster" to newClusterName,
                        "user" to newUserName
                    )
                )
                val contexts = kubeConfigMap["contexts"] as? MutableList<MutableMap<String, Any>> ?: mutableListOf()
                contexts.add(newContext)

                kubeConfigMap["clusters"] = clusters
                kubeConfigMap["users"] = users
                kubeConfigMap["contexts"] = contexts
                kubeConfigMap["current-context"] = newContextName
            }

            val writer = FileWriter(file)
            yaml.dump(kubeConfigMap, writer)
            writer.close()
        }
    }
}
