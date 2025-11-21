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
    fun `#apply creates the context if it does not exist`() {
        // given
        val data = CreateContextTestData()
        val config = KubeConfigTestHelpers.createMockKubeConfig(tempKubeConfigFile)
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, tempKubeConfigFile)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs)
        
        // when
        update.apply()
        
        // then
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                match { contexts ->
                    assertThat(contexts).hasSize(1)
                    verifyContext(contexts[0] as Map<*, *>, "${data.clusterName}/${data.clusterName}", data.clusterName, data.clusterName)
                },
                match { clusters ->
                    assertThat(clusters).hasSize(1)
                    verifyCluster(clusters[0] as Map<*, *>, data.clusterName, data.clusterUrl)
                },
                match { users ->
                    assertThat(users).hasSize(1)
                    verifyUser(users[0] as Map<*, *>, data.clusterName, data.token)
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply updates the token if context already exists`() {
        // given
        val data = UpdateTokenTestData()
        val (existingUserMap, existingClusterMap, existingContextMap) = createUpdateTokenTestMaps(data)
        val config = KubeConfigTestHelpers.createMockKubeConfig(tempKubeConfigFile, existingUserMap, existingClusterMap, existingContextMap)
        val allConfigs = listOf(config)
        val mockContext = setupUpdateTokenMocks(data, allConfigs, config, null)

        val update = KubeConfigUpdate.UpdateToken(data.clusterName, data.clusterUrl, data.newToken, mockContext, allConfigs)

        // when
        update.apply()

        // then
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                match { contexts ->
                    assertThat(contexts).hasSize(1)
                    verifyContext(contexts[0] as Map<*, *>, data.clusterName, data.clusterName, data.userName)
                },
                match { clusters ->
                    assertThat(clusters).hasSize(1)
                    verifyCluster(clusters[0] as Map<*, *>, data.clusterName, data.clusterUrl)
                },
                match { users ->
                    assertThat(users).hasSize(1)
                    verifyUser(users[0] as Map<*, *>, data.userName, data.newToken)
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply sets current context when updating token`() {
        // given
        val data = UpdateTokenTestData()
        val (existingUserMap, existingClusterMap, existingContextMap) = createUpdateTokenTestMaps(data)
        val configForToken = KubeConfigTestHelpers.createMockKubeConfig(tempKubeConfigFile, existingUserMap, existingClusterMap, existingContextMap, "other-context")
        val configForCurrentContext = KubeConfigTestHelpers.createMockKubeConfig(tempKubeConfigFile, existingUserMap, existingClusterMap, existingContextMap, "other-context")
        val allConfigs = listOf(configForToken, configForCurrentContext)
        val mockContext = setupUpdateTokenMocks(data, allConfigs, configForToken, configForCurrentContext)

        val update = KubeConfigUpdate.UpdateToken(data.clusterName, data.clusterUrl, data.newToken, mockContext, allConfigs)

        // when
        update.apply()

        // then - verify that save is called twice: once for token update, once for current context update
        verify(exactly = 2) {
            anyConstructed<BlockStyleFilePersister>().save(any(), any(), any(), any(), any())
        }
        
        // Verify both calls were made: first with original context, second with context name
        // The match function returns boolean - MockK will try calls until one matches
        verify(atLeast = 1) {
            anyConstructed<BlockStyleFilePersister>().save(
                any(),
                any(),
                any(),
                any(),
                match { currentContext -> 
                    currentContext == "other-context"
                },
            )
        }
        
        verify(atLeast = 1) {
            anyConstructed<BlockStyleFilePersister>().save(
                any(),
                any(),
                any(),
                any(),
                match { currentContext -> 
                    currentContext == data.contextName
                },
            )
        }
    }

    @Test
    fun `#apply does not set current context when no config has current context`() {
        // given
        val data = UpdateTokenTestData()
        val (existingUserMap, existingClusterMap, existingContextMap) = createUpdateTokenTestMaps(data)
        val config = KubeConfigTestHelpers.createMockKubeConfig(tempKubeConfigFile, existingUserMap, existingClusterMap, existingContextMap, "")
        val allConfigs = listOf(config)
        val mockContext = setupUpdateTokenMocks(data, allConfigs, config, null)

        val update = KubeConfigUpdate.UpdateToken(data.clusterName, data.clusterUrl, data.newToken, mockContext, allConfigs)

        // when
        update.apply()

        // then - verify that save is called only once (for token update, not for current context)
        verify(exactly = 1) {
            anyConstructed<BlockStyleFilePersister>().save(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `#apply generates unique user name if user name already exists`() {
        // given
        val data = CreateContextTestData()
        val existingUserMap = KubeConfigTestHelpers.createUserMap(data.clusterName, "existing-token")

        val config = KubeConfigTestHelpers.createMockKubeConfig(
            tempKubeConfigFile,
            users = listOf(existingUserMap)
        )
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, tempKubeConfigFile)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs)

        // when
        update.apply()

        // then
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                any(),
                any(),
                match { users ->
                    verifyNewEntryInList(users, 2, "${data.clusterName}2") { userMap ->
                        verifyUser(userMap, "${data.clusterName}2", data.token)
                    }
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply generates unique cluster name if cluster name already exists`() {
        // given
        val data = CreateContextTestData()
        val existingClusterMap = KubeConfigTestHelpers.createClusterMap(data.clusterName, "https://existing.com")

        val config = KubeConfigTestHelpers.createMockKubeConfig(
            tempKubeConfigFile,
            clusters = listOf(existingClusterMap)
        )
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, tempKubeConfigFile)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs)

        // when
        update.apply()

        // then
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                any(),
                match { clusters ->
                    verifyNewEntryInList(clusters, 2, "${data.clusterName}2") { clusterMap ->
                        verifyCluster(clusterMap, "${data.clusterName}2", data.clusterUrl)
                    }
                },
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply generates unique context name if context name already exists`() {
        // given
        val data = CreateContextTestData()
        val existingContextMap = KubeConfigTestHelpers.createContextMap("${data.clusterName}/${data.clusterName}", "other-cluster", "other-user")

        val config = KubeConfigTestHelpers.createMockKubeConfig(
            tempKubeConfigFile,
            contexts = listOf(existingContextMap)
        )
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, tempKubeConfigFile)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs)

        // when
        update.apply()

        // then
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                match { contexts ->
                    verifyNewEntryInList(contexts, 2, "${data.clusterName}/${data.clusterName}2") { contextMap ->
                        verifyContext(contextMap, "${data.clusterName}/${data.clusterName}2", data.clusterName, data.clusterName)
                    }
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
        val data = CreateContextTestData()
        // Existing entries: clusterName, clusterName2
        val existingUser1 = KubeConfigTestHelpers.createUserMap(data.clusterName, "token1")
        val existingUser2 = KubeConfigTestHelpers.createUserMap("${data.clusterName}2", "token2")

        val config = KubeConfigTestHelpers.createMockKubeConfig(
            tempKubeConfigFile,
            users = listOf(existingUser1, existingUser2)
        )
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, tempKubeConfigFile)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs)

        // when
        update.apply()

        // then
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                any(),
                any(),
                match { users ->
                    verifyNewEntryInList(users, 3, "${data.clusterName}3") { userMap ->
                        verifyUser(userMap, "${data.clusterName}3", data.token)
                    }
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply checks all configs when generating unique names`() {
        // given
        val data = CreateContextTestData()
        val existingUserMap = KubeConfigTestHelpers.createUserMap(data.clusterName, "existing-token")

        val config1 = KubeConfigTestHelpers.createMockKubeConfig(
            tempKubeConfigFile,
            users = listOf(existingUserMap)
        )
        val config2 = KubeConfigTestHelpers.createMockKubeConfig(tempKubeConfigFile)
        val allConfigs = listOf(config1, config2)
        setupCreateContextMocks(data.clusterName, allConfigs, tempKubeConfigFile)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs)

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
                    verifyNewEntryInList(users, 2, "${data.clusterName}2") { userMap ->
                        verifyUser(userMap, "${data.clusterName}2", data.token)
                    }
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply generates unique names for user cluster and context when all exist`() {
        // given
        val data = CreateContextTestData()
        val existingUserMap = KubeConfigTestHelpers.createUserMap(data.clusterName, "existing-token")
        val existingClusterMap = KubeConfigTestHelpers.createClusterMap(data.clusterName, "https://existing.com")
        val existingContextMap = KubeConfigTestHelpers.createContextMap("${data.clusterName}/${data.clusterName}", "other-cluster", "other-user")

        val config = KubeConfigTestHelpers.createMockKubeConfig(
            tempKubeConfigFile,
            contexts = listOf(existingContextMap),
            clusters = listOf(existingClusterMap),
            users = listOf(existingUserMap)
        )
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, tempKubeConfigFile)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs)

        // when
        update.apply()

        // then - all three should have unique names with suffix 2
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                match { contexts ->
                    verifyNewEntryInList(contexts, 2, "${data.clusterName}2/${data.clusterName}2") { contextMap ->
                        verifyContext(contextMap, "${data.clusterName}2/${data.clusterName}2", "${data.clusterName}2", "${data.clusterName}2")
                    }
                },
                match { clusters ->
                    verifyNewEntryInList(clusters, 2, "${data.clusterName}2") { clusterMap ->
                        verifyCluster(clusterMap, "${data.clusterName}2", data.clusterUrl)
                    }
                },
                match { users ->
                    verifyNewEntryInList(users, 2, "${data.clusterName}2") { userMap ->
                        verifyUser(userMap, "${data.clusterName}2", data.token)
                    }
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply sets current-context when a new context is created`() {
        // given
        val data = CreateContextTestData()
        val expectedContextName = "${data.clusterName}/${data.clusterName}"

        val config = KubeConfigTestHelpers.createMockKubeConfig(
            tempKubeConfigFile,
            setupContextCapture = { mockConfig ->
                // Capture the context name set via setContext() and return it via currentContext
                val contextNameSlot = slot<String>()
                every { mockConfig.setContext(capture(contextNameSlot)) } returns true
                every { mockConfig.currentContext } answers {
                    if (contextNameSlot.isCaptured) contextNameSlot.captured else ""
                }
            }
        )
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, tempKubeConfigFile)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs)
        
        // when
        update.apply()
        
        // then
        verify {
            anyConstructed<BlockStyleFilePersister>().save(
                any(),
                any(),
                any(),
                any(),
                match { currentContext -> verifyCurrentContext(currentContext, expectedContextName) },
            )
        }
    }

    private data class UpdateTokenTestData(
        val oldToken: String = "use-the-force",
        val newToken: String = "may-the-force-be-with-you",
        val clusterName: String = "tatooine",
        val clusterUrl: String = "https://tatooine.com",
        val userName: String = "luke-skywalker",
        val contextName: String = clusterName
    )

    private data class CreateContextTestData(
        val clusterName: String = "death-star",
        val clusterUrl: String = "https://death-star.com",
        val token: String = "join-the-dark-side"
    )

    // Helper functions for test verification and data creation

    private fun findEntryByName(list: List<*>, expectedName: String): Map<*, *>? {
        return list.find { entry ->
            val entryMap = entry as? Map<*, *> ?: return@find false
            val name = Utils.getValue(entryMap, arrayOf("name")) as? String
            name == expectedName
        } as? Map<*, *>
    }


    private fun verifyContext(context: Map<*, *>, expectedName: String, expectedCluster: String, expectedUser: String): Boolean {
        val contextName = Utils.getValue(context, arrayOf("name")) as String
        val cluster = Utils.getValue(context, arrayOf("context", "cluster")) as String
        val user = Utils.getValue(context, arrayOf("context", "user")) as String
        assertThat(contextName).isEqualTo(expectedName)
        assertThat(cluster).isEqualTo(expectedCluster)
        assertThat(user).isEqualTo(expectedUser)
        return true
    }

    private fun verifyCluster(cluster: Map<*, *>, expectedName: String, expectedServer: String): Boolean {
        val name = Utils.getValue(cluster, arrayOf("name")) as String
        val server = Utils.getValue(cluster, arrayOf("cluster", "server")) as String
        assertThat(name).isEqualTo(expectedName)
        assertThat(server).isEqualTo(expectedServer)
        return true
    }

    private fun verifyUser(user: Map<*, *>, expectedName: String, expectedToken: String): Boolean {
        val name = Utils.getValue(user, arrayOf("name")) as String
        val token = Utils.getValue(user, arrayOf("user", "token")) as String
        assertThat(name).isEqualTo(expectedName)
        assertThat(token).isEqualTo(expectedToken)
        return true
    }

    private fun verifyCurrentContext(currentContext: String?, expectedContextName: String): Boolean {
        assertThat(currentContext).isEqualTo(expectedContextName)
        return true
    }

    private fun verifyNewEntryInList(
        list: List<*>,
        expectedSize: Int,
        expectedName: String,
        verifyEntry: (Map<*, *>) -> Unit
    ): Boolean {
        assertThat(list).hasSize(expectedSize)
        val entry = findEntryByName(list, expectedName)
        assertThat(entry).isNotNull()
        verifyEntry(entry!!)
        return true
    }

    private fun createUpdateTokenTestMaps(data: UpdateTokenTestData): Triple<MutableMap<String, Any>, MutableMap<String, Any>, MutableMap<String, Any>> {
        val existingUserMap = KubeConfigTestHelpers.createUserMap(data.userName, data.oldToken)
        val existingClusterMap = KubeConfigTestHelpers.createClusterMap(data.clusterName, data.clusterUrl)
        val existingContextMap = KubeConfigTestHelpers.createContextMap(data.contextName, data.clusterName, data.userName)
        return Triple(existingUserMap, existingClusterMap, existingContextMap)
    }


    private fun setupUpdateTokenMocks(
        data: UpdateTokenTestData,
        allConfigs: List<KubeConfig>,
        configForUser: KubeConfig,
        configForCurrentContext: KubeConfig?
    ): KubeConfigNamedContext {
        mockkObject(KubeConfigNamedContext)
        val mockContext = mockk<KubeConfigNamedContext>(relaxed = true)
        every { mockContext.context } returns KubeConfigContext(data.userName, data.clusterName)
        every { mockContext.name } returns data.contextName
        every { KubeConfigNamedContext.getByClusterName(data.clusterName, allConfigs) } returns mockContext
        every { KubeConfigUtils.getAllConfigs(any()) } returns allConfigs
        every { KubeConfigUtils.getAllConfigFiles() } returns listOf(tempKubeConfigFile)
        every { KubeConfigUtils.getConfigByUser(mockContext, allConfigs) } returns configForUser
        every { KubeConfigUtils.getConfigWithCurrentContext(allConfigs) } returns configForCurrentContext
        return mockContext
    }


    private fun setupCreateContextMocks(
        clusterName: String,
        allConfigs: List<KubeConfig>,
        path: Path
    ) {
        mockkObject(KubeConfigNamedContext)
        every { KubeConfigNamedContext.getByClusterName(clusterName, allConfigs) } returns null
        every { KubeConfigUtils.getAllConfigs(any()) } returns allConfigs
        every { KubeConfigUtils.getAllConfigFiles() } returns listOf(path)
    }
}
