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

import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils.path
import io.kubernetes.client.util.KubeConfig
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Path

object KubeConfigTestHelpers {

    /**
     * Creates a mock KubeConfig with optional contexts, users, and clusters
     */
    fun createMockKubeConfig(
        contexts: List<Map<String, Any>>? = null,
        users: List<Map<String, Any>>? = null,
        clusters: List<Map<String, Any>>? = null,
        credentials: Map<String, String>? = null
    ): KubeConfig {
        val kubeConfig = mockk<KubeConfig>(relaxed = true)
        // Stub currentContext FIRST to prevent MockK from trying to set the backing field
        // Use answers instead of returns to avoid MockK trying to set the Map backing field
        every { kubeConfig.currentContext } answers { "" }
        contexts?.let { every { kubeConfig.contexts } returns ArrayList(it) }
        users?.let { every { kubeConfig.users } returns ArrayList(it) }
        clusters?.let { every { kubeConfig.clusters } returns ArrayList(it) }
        credentials?.let { every { kubeConfig.credentials } returns it }
        return kubeConfig
    }

    /**
     * Creates a mock KubeConfig with individual maps, path, and optional current context.
     * This overload is useful for UpdateToken tests that need path and currentContext.
     */
    fun createMockKubeConfig(
        path: Path,
        userMap: MutableMap<String, Any>,
        clusterMap: MutableMap<String, Any>,
        contextMap: MutableMap<String, Any>,
        currentContext: String? = null
    ): KubeConfig {
        val config = mockk<KubeConfig>(relaxed = true)
        // Stub currentContext FIRST to prevent MockK from trying to set the backing field
        // Use answers instead of returns to avoid MockK trying to set the Map backing field
        if (!currentContext.isNullOrBlank()) {
            every { config.currentContext } answers { currentContext }
        } else {
            every { config.currentContext } answers { "" }
        }
        every { config.contexts } returns ArrayList(listOf(contextMap))
        every { config.clusters } returns ArrayList(listOf(clusterMap))
        every { config.users } returns ArrayList(listOf(userMap))
        every { config.path } returns path
        every { config.preferences } returns mockk()
        return config
    }

    /**
     * Creates a mock KubeConfig with lists of maps, path, and optional setup callback.
     * This overload is useful for CreateContext tests that need setupContextCapture.
     */
    fun createMockKubeConfig(
        path: Path,
        contexts: List<MutableMap<String, Any>> = emptyList(),
        clusters: List<MutableMap<String, Any>> = emptyList(),
        users: List<MutableMap<String, Any>> = emptyList(),
        setupContextCapture: ((KubeConfig) -> Unit)? = null
    ): KubeConfig {
        val config = mockk<KubeConfig>(relaxed = true)
        // Stub currentContext FIRST to prevent MockK from trying to set the backing field
        // Use answers instead of returns to avoid MockK trying to set the Map backing field
        every { config.currentContext } answers { "" }
        every { config.contexts } returns ArrayList(contexts)
        every { config.clusters } returns ArrayList(clusters)
        every { config.users } returns ArrayList(users)
        every { config.path } returns path
        every { config.preferences } returns mockk()
        setupContextCapture?.invoke(config)
        return config
    }

    /**
     * Creates a context map for testing
     */
    fun createContextMap(name: String, cluster: String, user: String): MutableMap<String, Any> {
        return mutableMapOf(
            "name" to name,
            "context" to mutableMapOf(
                "cluster" to cluster,
                "user" to user
            )
        )
    }

    /**
     * Creates a user map for testing
     */
    fun createUserMap(name: String, token: String): MutableMap<String, Any> {
        return mutableMapOf(
            "name" to name,
            "user" to mutableMapOf("token" to token)
        )
    }

    /**
     * Creates a cluster map for testing
     */
    fun createClusterMap(name: String, server: String): MutableMap<String, Any> {
        return mutableMapOf(
            "name" to name,
            "cluster" to mutableMapOf("server" to server)
        )
    }
}

