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
import com.redhat.devtools.gateway.openshift.Utils
import io.kubernetes.client.util.KubeConfig
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class KubeConfigUpdateTest {

    private lateinit var tempKubeConfigFile: Path

    @BeforeEach
    fun before() {
        mockkObject(KubeConfigUtils)
        mockkObject(Utils)
        mockkConstructor(BlockStyleFilePersister::class)
        every { anyConstructed<BlockStyleFilePersister>().save(any(), any(), any(), any(), any()) } returns Unit
        this.tempKubeConfigFile = Files.createTempFile("test-kubeconfig", ".tmp")
    }

    @AfterEach
    fun after() {
        unmockkConstructor(BlockStyleFilePersister::class)
        unmockkAll()
        clearAllMocks()
        Files.deleteIfExists(tempKubeConfigFile)
    }

    @Test
    fun `#apply does create context if context does not exist`() {
        // given
        val path = tempKubeConfigFile
        val clusterName = "death-star"
        val clusterUrl = "https://death-star.com"
        val token = "join-the-dark-side"

        val config = mockk<KubeConfig>(relaxed = true)
        every { config.contexts } returns ArrayList()
        every { config.clusters } returns ArrayList()
        every { config.users } returns ArrayList()
        every { config.path } returns path
        every { config.preferences } returns mockk()

        val allConfigs = listOf(config)

        mockkObject(KubeConfigNamedContext)
        every { KubeConfigNamedContext.getByClusterName(clusterName, allConfigs) } returns null
        every { KubeConfigUtils.getAllConfigs(any()) } returns allConfigs
        every { KubeConfigUtils.getAllConfigFiles() } returns listOf(path)

        val update = KubeConfigUpdate.CreateContext(clusterName, clusterUrl, token, allConfigs)
        
        // when
        update.apply()
        
        // then
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                match { contexts ->
                    assertThat(contexts).hasSize(1)
                    val context = contexts[0] as Map<*, *>
                    // Context name is user/cluster format: "death-star/death-star" since both user and cluster names are "death-star"
                    val contextName = Utils.getValue(context, arrayOf("name")) as String
                    assertThat(contextName).isEqualTo("$clusterName/$clusterName")
                    assertThat(Utils.getValue(context, arrayOf("context", "cluster"))).isEqualTo(clusterName)
                    assertThat(Utils.getValue(context, arrayOf("context", "user"))).isEqualTo(clusterName)
                    true
                },
                match { clusters ->
                    assertThat(clusters).hasSize(1)
                    val cluster = clusters[0] as Map<*, *>
                    assertThat(Utils.getValue(cluster, arrayOf("name"))).isEqualTo(clusterName)
                    assertThat(Utils.getValue(cluster, arrayOf("cluster", "server"))).isEqualTo(clusterUrl)
                    true
                },
                match { users ->
                    assertThat(users).hasSize(1)
                    val user = users[0] as Map<*, *>
                    assertThat(Utils.getValue(user, arrayOf("name"))).isEqualTo(clusterName)
                    assertThat(Utils.getValue(user, arrayOf("user", "token"))).isEqualTo(token)
                    true
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply does update token if context already exists`() {
        // given
        val path = tempKubeConfigFile
        val oldToken = "use-the-force"
        val newToken = "may-the-force-be-with-you"
        val clusterName = "tatooine"
        val clusterUrl = "https://tatooine.com"
        val userName = "luke-skywalker"

        val existingUserMap = mutableMapOf<String, Any>(
            "name" to userName,
            "user" to mutableMapOf("token" to oldToken)
        )
        val existingClusterMap = mutableMapOf<String, Any>(
            "name" to clusterName,
            "cluster" to mutableMapOf("server" to clusterUrl)
        )
        val existingContextMap = mutableMapOf<String, Any>(
            "name" to clusterName,
            "context" to mutableMapOf("cluster" to clusterName, "user" to userName)
        )

        val config = mockk<KubeConfig>(relaxed = true)
        every { config.contexts } returns ArrayList(listOf(existingContextMap))
        every { config.clusters } returns ArrayList(listOf(existingClusterMap))
        every { config.users } returns ArrayList(listOf(existingUserMap))
        every { config.path } returns path
        every { config.preferences } returns mockk()

        val allConfigs = listOf(config)

        mockkObject(KubeConfigNamedContext)
        val mockContext = mockk<KubeConfigNamedContext>(relaxed = true)
        every { mockContext.context } returns KubeConfigContext(userName, clusterName)
        every { KubeConfigNamedContext.getByClusterName(clusterName, allConfigs) } returns mockContext
        every { KubeConfigUtils.getAllConfigs(any()) } returns allConfigs
        every { KubeConfigUtils.getAllConfigFiles() } returns listOf(path)
        every { KubeConfigUtils.getConfigByUser(mockContext, allConfigs) } returns config

        val update = KubeConfigUpdate.UpdateToken(clusterName, clusterUrl, newToken, mockContext, allConfigs)

        // when
        update.apply()

        // then
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                match { contexts ->
                    assertThat(contexts).hasSize(1)
                    val context = contexts[0] as Map<*, *>
                    assertThat(Utils.getValue(context, arrayOf("name"))).isEqualTo(clusterName)
                    assertThat(Utils.getValue(context, arrayOf("context", "cluster"))).isEqualTo(clusterName)
                    assertThat(Utils.getValue(context, arrayOf("context", "user"))).isEqualTo(userName)
                    true
                },
                match { clusters ->
                    assertThat(clusters).hasSize(1)
                    val cluster = clusters[0] as Map<*, *>
                    assertThat(Utils.getValue(cluster, arrayOf("name"))).isEqualTo(clusterName)
                    assertThat(Utils.getValue(cluster, arrayOf("cluster", "server"))).isEqualTo(clusterUrl)
                    true
                },
                match { users ->
                    assertThat(users).hasSize(1)
                    val user = users[0] as Map<*, *>
                    assertThat(Utils.getValue(user, arrayOf("name"))).isEqualTo(userName)
                    assertThat(Utils.getValue(user, arrayOf("user", "token"))).isEqualTo(newToken)
                    true
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply generates unique user name when user name already exists`() {
        // given
        val path = tempKubeConfigFile
        val clusterName = "death-star"
        val clusterUrl = "https://death-star.com"
        val token = "join-the-dark-side"
        val existingUserName = clusterName

        val existingUserMap = mutableMapOf<String, Any>(
            "name" to existingUserName,
            "user" to mutableMapOf("token" to "existing-token")
        )

        val config = mockk<KubeConfig>(relaxed = true)
        every { config.contexts } returns ArrayList()
        every { config.clusters } returns ArrayList()
        every { config.users } returns ArrayList(listOf(existingUserMap))
        every { config.path } returns path
        every { config.preferences } returns mockk()

        val allConfigs = listOf(config)

        mockkObject(KubeConfigNamedContext)
        every { KubeConfigNamedContext.getByClusterName(clusterName, allConfigs) } returns null
        every { KubeConfigUtils.getAllConfigs(any()) } returns allConfigs
        every { KubeConfigUtils.getAllConfigFiles() } returns listOf(path)

        val update = KubeConfigUpdate.CreateContext(clusterName, clusterUrl, token, allConfigs)

        // when
        update.apply()

        // then
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                any(),
                any(),
                match { users ->
                    assertThat(users).hasSize(2) // existing + new
                    val newUser = users.find { user ->
                        val userMap = user as Map<*, *>
                        val name = Utils.getValue(userMap, arrayOf("name")) as String
                        name == "$clusterName-1"
                    }
                    assertThat(newUser).isNotNull
                    val newUserMap = newUser as Map<*, *>
                    assertThat(Utils.getValue(newUserMap, arrayOf("name"))).isEqualTo("$clusterName-1")
                    assertThat(Utils.getValue(newUserMap, arrayOf("user", "token"))).isEqualTo(token)
                    true
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply generates unique cluster name when cluster name already exists`() {
        // given
        val path = tempKubeConfigFile
        val clusterName = "death-star"
        val clusterUrl = "https://death-star.com"
        val token = "join-the-dark-side"
        val existingClusterName = clusterName

        val existingClusterMap = mutableMapOf<String, Any>(
            "name" to existingClusterName,
            "cluster" to mutableMapOf("server" to "https://existing.com")
        )

        val config = mockk<KubeConfig>(relaxed = true)
        every { config.contexts } returns ArrayList()
        every { config.clusters } returns ArrayList(listOf(existingClusterMap))
        every { config.users } returns ArrayList()
        every { config.path } returns path
        every { config.preferences } returns mockk()

        val allConfigs = listOf(config)

        mockkObject(KubeConfigNamedContext)
        every { KubeConfigNamedContext.getByClusterName(clusterName, allConfigs) } returns null
        every { KubeConfigUtils.getAllConfigs(any()) } returns allConfigs
        every { KubeConfigUtils.getAllConfigFiles() } returns listOf(path)

        val update = KubeConfigUpdate.CreateContext(clusterName, clusterUrl, token, allConfigs)

        // when
        update.apply()

        // then
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                any(),
                match { clusters ->
                    assertThat(clusters).hasSize(2) // existing + new
                    val newCluster = clusters.find { cluster ->
                        val clusterMap = cluster as Map<*, *>
                        val name = Utils.getValue(clusterMap, arrayOf("name")) as String
                        name == "$clusterName-1"
                    }
                    assertThat(newCluster).isNotNull
                    val newClusterMap = newCluster as Map<*, *>
                    assertThat(Utils.getValue(newClusterMap, arrayOf("name"))).isEqualTo("$clusterName-1")
                    assertThat(Utils.getValue(newClusterMap, arrayOf("cluster", "server"))).isEqualTo(clusterUrl)
                    true
                },
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply generates unique context name when context name already exists`() {
        // given
        val path = tempKubeConfigFile
        val clusterName = "death-star"
        val clusterUrl = "https://death-star.com"
        val token = "join-the-dark-side"
        val existingContextName = "$clusterName/$clusterName"

        val existingContextMap = mutableMapOf<String, Any>(
            "name" to existingContextName,
            "context" to mutableMapOf("cluster" to "other-cluster", "user" to "other-user")
        )

        val config = mockk<KubeConfig>(relaxed = true)
        every { config.contexts } returns ArrayList(listOf(existingContextMap))
        every { config.clusters } returns ArrayList()
        every { config.users } returns ArrayList()
        every { config.path } returns path
        every { config.preferences } returns mockk()

        val allConfigs = listOf(config)

        mockkObject(KubeConfigNamedContext)
        every { KubeConfigNamedContext.getByClusterName(clusterName, allConfigs) } returns null
        every { KubeConfigUtils.getAllConfigs(any()) } returns allConfigs
        every { KubeConfigUtils.getAllConfigFiles() } returns listOf(path)

        val update = KubeConfigUpdate.CreateContext(clusterName, clusterUrl, token, allConfigs)

        // when
        update.apply()

        // then
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                match { contexts ->
                    assertThat(contexts).hasSize(2) // existing + new
                    val newContext = contexts.find { context ->
                        val contextMap = context as Map<*, *>
                        val name = Utils.getValue(contextMap, arrayOf("name")) as String
                        name == "$existingContextName-1"
                    }
                    assertThat(newContext).isNotNull
                    val newContextMap = newContext as Map<*, *>
                    assertThat(Utils.getValue(newContextMap, arrayOf("name"))).isEqualTo("$existingContextName-1")
                    assertThat(Utils.getValue(newContextMap, arrayOf("context", "cluster"))).isEqualTo(clusterName)
                    assertThat(Utils.getValue(newContextMap, arrayOf("context", "user"))).isEqualTo(clusterName)
                    true
                },
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply generates sequential unique names when multiple entries exist`() {
        // given
        val path = tempKubeConfigFile
        val clusterName = "death-star"
        val clusterUrl = "https://death-star.com"
        val token = "join-the-dark-side"

        // Existing entries: clusterName, clusterName-1
        val existingUser1 = mutableMapOf<String, Any>(
            "name" to clusterName,
            "user" to mutableMapOf("token" to "token1")
        )
        val existingUser2 = mutableMapOf<String, Any>(
            "name" to "$clusterName-1",
            "user" to mutableMapOf("token" to "token2")
        )

        val config = mockk<KubeConfig>(relaxed = true)
        every { config.contexts } returns ArrayList()
        every { config.clusters } returns ArrayList()
        every { config.users } returns ArrayList(listOf(existingUser1, existingUser2))
        every { config.path } returns path
        every { config.preferences } returns mockk()

        val allConfigs = listOf(config)

        mockkObject(KubeConfigNamedContext)
        every { KubeConfigNamedContext.getByClusterName(clusterName, allConfigs) } returns null
        every { KubeConfigUtils.getAllConfigs(any()) } returns allConfigs
        every { KubeConfigUtils.getAllConfigFiles() } returns listOf(path)

        val update = KubeConfigUpdate.CreateContext(clusterName, clusterUrl, token, allConfigs)

        // when
        update.apply()

        // then
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                any(),
                any(),
                match { users ->
                    assertThat(users).hasSize(3) // 2 existing + 1 new
                    val newUser = users.find { user ->
                        val userMap = user as Map<*, *>
                        val name = Utils.getValue(userMap, arrayOf("name")) as String
                        name == "$clusterName-2"
                    }
                    assertThat(newUser).isNotNull
                    val newUserMap = newUser as Map<*, *>
                    assertThat(Utils.getValue(newUserMap, arrayOf("name"))).isEqualTo("$clusterName-2")
                    assertThat(Utils.getValue(newUserMap, arrayOf("user", "token"))).isEqualTo(token)
                    true
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply checks all configs when generating unique names`() {
        // given
        val path = tempKubeConfigFile
        val clusterName = "death-star"
        val clusterUrl = "https://death-star.com"
        val token = "join-the-dark-side"

        val existingUserMap = mutableMapOf<String, Any>(
            "name" to clusterName,
            "user" to mutableMapOf("token" to "existing-token")
        )

        val config1 = mockk<KubeConfig>(relaxed = true)
        every { config1.contexts } returns ArrayList()
        every { config1.clusters } returns ArrayList()
        every { config1.users } returns ArrayList(listOf(existingUserMap))
        every { config1.path } returns path
        every { config1.preferences } returns mockk()

        val config2 = mockk<KubeConfig>(relaxed = true)
        every { config2.contexts } returns ArrayList()
        every { config2.clusters } returns ArrayList()
        every { config2.users } returns ArrayList()
        every { config2.path } returns path
        every { config2.preferences } returns mockk()

        val allConfigs = listOf(config1, config2)

        mockkObject(KubeConfigNamedContext)
        every { KubeConfigNamedContext.getByClusterName(clusterName, allConfigs) } returns null
        every { KubeConfigUtils.getAllConfigs(any()) } returns allConfigs
        every { KubeConfigUtils.getAllConfigFiles() } returns listOf(path)

        val update = KubeConfigUpdate.CreateContext(clusterName, clusterUrl, token, allConfigs)

        // when
        update.apply()

        // then - should generate unique name even though the duplicate is in a different config
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                any(),
                any(),
                match { users ->
                    // config1 has 1 existing user, config2 will have 1 new user added
                    // But we're only saving config1 (first config), so it should have 2 users total
                    assertThat(users).hasSize(2) // existing + new
                    val newUser = users.find { user ->
                        val userMap = user as Map<*, *>
                        val name = Utils.getValue(userMap, arrayOf("name")) as String
                        name == "$clusterName-1"
                    }
                    assertThat(newUser).isNotNull
                    val newUserMap = newUser as Map<*, *>
                    assertThat(Utils.getValue(newUserMap, arrayOf("name"))).isEqualTo("$clusterName-1")
                    assertThat(Utils.getValue(newUserMap, arrayOf("user", "token"))).isEqualTo(token)
                    true
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply generates unique names for user cluster and context when all exist`() {
        // given
        val path = tempKubeConfigFile
        val clusterName = "death-star"
        val clusterUrl = "https://death-star.com"
        val token = "join-the-dark-side"

        val existingUserMap = mutableMapOf<String, Any>(
            "name" to clusterName,
            "user" to mutableMapOf("token" to "existing-token")
        )
        val existingClusterMap = mutableMapOf<String, Any>(
            "name" to clusterName,
            "cluster" to mutableMapOf("server" to "https://existing.com")
        )
        val existingContextMap = mutableMapOf<String, Any>(
            "name" to "$clusterName/$clusterName",
            "context" to mutableMapOf("cluster" to "other-cluster", "user" to "other-user")
        )

        val config = mockk<KubeConfig>(relaxed = true)
        every { config.contexts } returns ArrayList(listOf(existingContextMap))
        every { config.clusters } returns ArrayList(listOf(existingClusterMap))
        every { config.users } returns ArrayList(listOf(existingUserMap))
        every { config.path } returns path
        every { config.preferences } returns mockk()

        val allConfigs = listOf(config)

        mockkObject(KubeConfigNamedContext)
        every { KubeConfigNamedContext.getByClusterName(clusterName, allConfigs) } returns null
        every { KubeConfigUtils.getAllConfigs(any()) } returns allConfigs
        every { KubeConfigUtils.getAllConfigFiles() } returns listOf(path)

        val update = KubeConfigUpdate.CreateContext(clusterName, clusterUrl, token, allConfigs)

        // when
        update.apply()

        // then - all three should have unique names with suffix -1
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                match { contexts ->
                    assertThat(contexts).hasSize(2) // existing + new
                    val newContext = contexts.find { context ->
                        val contextMap = context as Map<*, *>
                        val name = Utils.getValue(contextMap, arrayOf("name")) as String
                        name == "$clusterName-1/$clusterName-1"
                    }
                    assertThat(newContext).isNotNull
                    val newContextMap = newContext as Map<*, *>
                    assertThat(Utils.getValue(newContextMap, arrayOf("name"))).isEqualTo("$clusterName-1/$clusterName-1")
                    assertThat(Utils.getValue(newContextMap, arrayOf("context", "cluster"))).isEqualTo("$clusterName-1")
                    assertThat(Utils.getValue(newContextMap, arrayOf("context", "user"))).isEqualTo("$clusterName-1")
                    true
                },
                match { clusters ->
                    assertThat(clusters).hasSize(2) // existing + new
                    val newCluster = clusters.find { cluster ->
                        val clusterMap = cluster as Map<*, *>
                        val name = Utils.getValue(clusterMap, arrayOf("name")) as String
                        name == "$clusterName-1"
                    }
                    assertThat(newCluster).isNotNull
                    val newClusterMap = newCluster as Map<*, *>
                    assertThat(Utils.getValue(newClusterMap, arrayOf("name"))).isEqualTo("$clusterName-1")
                    assertThat(Utils.getValue(newClusterMap, arrayOf("cluster", "server"))).isEqualTo(clusterUrl)
                    true
                },
                match { users ->
                    assertThat(users).hasSize(2) // existing + new
                    val newUser = users.find { user ->
                        val userMap = user as Map<*, *>
                        val name = Utils.getValue(userMap, arrayOf("name")) as String
                        name == "$clusterName-1"
                    }
                    assertThat(newUser).isNotNull
                    val newUserMap = newUser as Map<*, *>
                    assertThat(Utils.getValue(newUserMap, arrayOf("name"))).isEqualTo("$clusterName-1")
                    assertThat(Utils.getValue(newUserMap, arrayOf("user", "token"))).isEqualTo(token)
                    true
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply sets current-context when a new context is created`() {
        // given
        val path = tempKubeConfigFile
        val clusterName = "death-star"
        val clusterUrl = "https://death-star.com"
        val token = "join-the-dark-side"
        val expectedContextName = "$clusterName/$clusterName"

        val config = mockk<KubeConfig>(relaxed = true)
        every { config.contexts } returns ArrayList()
        every { config.clusters } returns ArrayList()
        every { config.users } returns ArrayList()
        every { config.path } returns path
        every { config.preferences } returns mockk()
        
        // Capture the context name set via setContext() and return it via currentContext
        val contextNameSlot = slot<String>()
        every { config.setContext(capture(contextNameSlot)) } returns true
        every { config.currentContext } answers { 
            if (contextNameSlot.isCaptured) contextNameSlot.captured else null
        }

        val allConfigs = listOf(config)

        mockkObject(KubeConfigNamedContext)
        every { KubeConfigNamedContext.getByClusterName(clusterName, allConfigs) } returns null
        every { KubeConfigUtils.getAllConfigs(any()) } returns allConfigs
        every { KubeConfigUtils.getAllConfigFiles() } returns listOf(path)

        val update = KubeConfigUpdate.CreateContext(clusterName, clusterUrl, token, allConfigs)
        
        // when
        update.apply()
        
        // then
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                any(),
                any(),
                any(),
                any(),
                match { currentContext ->
                    assertThat(currentContext).isEqualTo(expectedContextName)
                    true
                },
            )
        }
    }
}
