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

import com.redhat.devtools.gateway.auth.tls.PemUtils
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils.path
import com.redhat.devtools.gateway.openshift.Utils
import io.kubernetes.client.persister.ConfigPersister
import io.kubernetes.client.util.KubeConfig
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path

class KubeConfigUpdateTest {

    private val kubeConfigPath = Path.of("/test/kubeconfig-primary")
    private val persistersByFile = mutableMapOf<File, ConfigPersister>()
    private val testPersisterFactory: (File) -> ConfigPersister = { file ->
        persistersByFile.getOrPut(file) { mockk(relaxed = true) }
    }

    private fun persisterFor(path: Path): ConfigPersister {
        return persistersByFile.getOrPut(path.toFile()) { mockk(relaxed = true) }
    }

    @BeforeEach
    fun before() {
        mockkObject(KubeConfigUtils)
        mockkObject(Utils)
    }

    @AfterEach
    fun after() {
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `#apply creates the context if it does not exist`() {
        // given
        val data = CreateContextTestData()
        val config = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath)
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, kubeConfigPath)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs, testPersisterFactory)
        
        // when
        update.apply()
        
        // then
        verify {
            persisterFor(kubeConfigPath).save(
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
    fun `#apply CreateContextWithClientCert creates the context if it does not exist`() {
        // given
        val data = CreateContextWithClientCertTestData()
        val config = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath)
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, kubeConfigPath)

        val update = KubeConfigUpdate.CreateContextWithClientCert(
            data.clusterName,
            data.clusterUrl,
            data.clientCertPem,
            data.clientKeyPem,
            allConfigs,
            testPersisterFactory,
        )

        // when
        update.apply()

        // then
        verify {
            persisterFor(kubeConfigPath).save(
                match { contexts ->
                    assertThat(contexts).hasSize(1)
                    verifyContext(
                        contexts[0] as Map<*, *>,
                        "${data.clusterName}/${data.clusterName}",
                        data.clusterName,
                        data.clusterName
                    )
                },
                match { clusters ->
                    assertThat(clusters).hasSize(1)
                    verifyCluster(clusters[0] as Map<*, *>, data.clusterName, data.clusterUrl)
                },
                match { users ->
                    assertThat(users).hasSize(1)
                    verifyUserWithClientCert(
                        users[0] as Map<*, *>,
                        data.clusterName,
                        data.clientCertPem,
                        data.clientKeyPem
                    )
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply CreateContextWithClientCert generates unique user name if user name already exists`() {
        // given
        val data = CreateContextWithClientCertTestData()
        val existingUserMap = KubeConfigTestHelpers.createUserMap(data.clusterName, "existing-token")

        val config = KubeConfigTestHelpers.createMockKubeConfig(
            kubeConfigPath,
            users = listOf(existingUserMap)
        )
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, kubeConfigPath)

        val update = KubeConfigUpdate.CreateContextWithClientCert(
            data.clusterName,
            data.clusterUrl,
            data.clientCertPem,
            data.clientKeyPem,
            allConfigs,
            testPersisterFactory,
        )

        // when
        update.apply()

        // then
        verify {
            persisterFor(kubeConfigPath).save(
                any(),
                any(),
                match { users ->
                    verifyNewEntryInList(users, 2, "${data.clusterName}2") { userMap ->
                        verifyUserWithClientCert(
                            userMap,
                            "${data.clusterName}2",
                            data.clientCertPem,
                            data.clientKeyPem
                        )
                    }
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
        val config = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath, existingUserMap, existingClusterMap, existingContextMap)
        val allConfigs = listOf(config)
        val mockContext = setupUpdateExistingContextMocks(data.clusterName, data.userName, data.contextName, allConfigs, config, null)

        val update = KubeConfigUpdate.UpdateToken(data.clusterName, data.clusterUrl, data.newToken, mockContext, allConfigs, testPersisterFactory)

        // when
        update.apply()

        // then
        verify {
            persisterFor(kubeConfigPath).save(
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
        val configForToken = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath, existingUserMap, existingClusterMap, existingContextMap, "other-context")
        val configForCurrentContext = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath, existingUserMap, existingClusterMap, existingContextMap, "other-context")
        val allConfigs = listOf(configForToken, configForCurrentContext)
        val mockContext = setupUpdateExistingContextMocks(data.clusterName, data.userName, data.contextName, allConfigs, configForToken, configForCurrentContext)

        val update = KubeConfigUpdate.UpdateToken(data.clusterName, data.clusterUrl, data.newToken, mockContext, allConfigs, testPersisterFactory)

        // when
        update.apply()

        // then - token and current-context changes are persisted in a single save
        verify(exactly = 1) {
            persisterFor(kubeConfigPath).save(any(), any(), any(), any(), any())
        }

        verify {
            persisterFor(kubeConfigPath).save(
                any(),
                any(),
                match { users ->
                    assertThat(users).hasSize(1)
                    verifyUser(users[0] as Map<*, *>, data.userName, data.newToken)
                },
                any(),
                match { currentContext ->
                    currentContext == data.contextName
                },
            )
        }
    }

    @Test
    fun `#apply UpdateToken saves user and current-context configs separately when on different files`() {
        // given
        val data = UpdateTokenTestData()
        val (existingUserMap, existingClusterMap, existingContextMap) = createUpdateTokenTestMaps(data)
        val userConfigFile = Path.of("/test/kubeconfig-user")
        val currentContextConfigFile = Path.of("/test/kubeconfig-current-context")
        val configForUser = KubeConfigTestHelpers.createMockKubeConfig(
            userConfigFile,
            existingUserMap,
            existingClusterMap,
            existingContextMap,
            ""
        )
        val configForCurrentContext = KubeConfigTestHelpers.createMockKubeConfig(
            currentContextConfigFile,
            existingUserMap,
            existingClusterMap,
            existingContextMap,
            "other-context"
        )
        val allConfigs = listOf(configForUser, configForCurrentContext)
        val mockContext = setupUpdateExistingContextMocks(
            data.clusterName,
            data.userName,
            data.contextName,
            allConfigs,
            configForUser,
            configForCurrentContext
        )

        val update = KubeConfigUpdate.UpdateToken(
            data.clusterName,
            data.clusterUrl,
            data.newToken,
            mockContext,
            allConfigs,
            testPersisterFactory,
        )

        // when
        update.apply()

        // then - user config and current-context config are persisted separately, each by its own persister
        val userFile = userConfigFile.toFile()
        val currentContextFile = currentContextConfigFile.toFile()
        val userPersister = persistersByFile[userFile]
        val currentContextPersister = persistersByFile[currentContextFile]

        verify(exactly = 1) {
            userPersister!!.save(
                any(),
                any(),
                match { users ->
                    assertThat(users).hasSize(1)
                    verifyUser(users[0] as Map<*, *>, data.userName, data.newToken)
                },
                any(),
                any(),
            )
        }

        verify(exactly = 1) {
            currentContextPersister!!.save(
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
    fun `#apply UpdateClientCert updates client cert if context already exists`() {
        // given
        val data = UpdateClientCertTestData()
        val (existingUserMap, existingClusterMap, existingContextMap) = createUpdateClientCertTestMaps(data)
        val config = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath, existingUserMap, existingClusterMap, existingContextMap)
        val allConfigs = listOf(config)
        val mockContext = setupUpdateExistingContextMocks(data.clusterName, data.userName, data.contextName, allConfigs, config, null)

        val update = KubeConfigUpdate.UpdateClientCert(
            data.clusterName,
            data.clusterUrl,
            data.clientCertPem,
            data.clientKeyPem,
            mockContext,
            allConfigs,
            testPersisterFactory,
        )

        // when
        update.apply()

        // then
        verify {
            persisterFor(kubeConfigPath).save(
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
                    verifyUserWithClientCert(users[0] as Map<*, *>, data.userName, data.clientCertPem, data.clientKeyPem)
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply UpdateClientCert sets current context when updating client cert`() {
        // given
        val data = UpdateClientCertTestData()
        val (existingUserMap, existingClusterMap, existingContextMap) = createUpdateClientCertTestMaps(data)
        val configForUser = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath, existingUserMap, existingClusterMap, existingContextMap, "other-context")
        val configForCurrentContext = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath, existingUserMap, existingClusterMap, existingContextMap, "other-context")
        val allConfigs = listOf(configForUser, configForCurrentContext)
        val mockContext = setupUpdateExistingContextMocks(data.clusterName, data.userName, data.contextName, allConfigs, configForUser, configForCurrentContext)

        val update = KubeConfigUpdate.UpdateClientCert(
            data.clusterName,
            data.clusterUrl,
            data.clientCertPem,
            data.clientKeyPem,
            mockContext,
            allConfigs,
            testPersisterFactory,
        )

        // when
        update.apply()

        // then - cert and current-context changes are persisted in a single save
        verify(exactly = 1) {
            persisterFor(kubeConfigPath).save(any(), any(), any(), any(), any())
        }

        verify {
            persisterFor(kubeConfigPath).save(
                any(),
                any(),
                match { users ->
                    assertThat(users).hasSize(1)
                    verifyUserWithClientCert(users[0] as Map<*, *>, data.userName, data.clientCertPem, data.clientKeyPem)
                },
                any(),
                match { currentContext ->
                    currentContext == data.contextName
                },
            )
        }
    }

    @Test
    fun `#apply UpdateClientCert sets current context on primary config when no config has current context`() {
        // given
        val data = UpdateClientCertTestData()
        val (existingUserMap, existingClusterMap, existingContextMap) = createUpdateClientCertTestMaps(data)
        val config = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath, existingUserMap, existingClusterMap, existingContextMap, "")
        val allConfigs = listOf(config)
        val mockContext = setupUpdateExistingContextMocks(data.clusterName, data.userName, data.contextName, allConfigs, config, null)

        val update = KubeConfigUpdate.UpdateClientCert(
            data.clusterName,
            data.clusterUrl,
            data.clientCertPem,
            data.clientKeyPem,
            mockContext,
            allConfigs,
            testPersisterFactory,
        )

        // when
        update.apply()

        // then - cert and current-context are persisted in a single save on the primary config
        verify(exactly = 1) {
            persisterFor(kubeConfigPath).save(any(), any(), any(), any(), any())
        }

        verify {
            persisterFor(kubeConfigPath).save(
                any(),
                any(),
                match { users ->
                    assertThat(users).hasSize(1)
                    verifyUserWithClientCert(users[0] as Map<*, *>, data.userName, data.clientCertPem, data.clientKeyPem)
                },
                any(),
                match { currentContext ->
                    currentContext == data.contextName
                },
            )
        }
    }

    @Test
    fun `#apply UpdateClientCert removes token when setting client cert`() {
        // given
        val data = UpdateClientCertWithTokenTestData()
        val existingUserMap = KubeConfigTestHelpers.createUserMapWithClientCert(
            data.userName,
            data.oldToken,
            data.oldClientCertPem,
            data.oldClientKeyPem
        )
        val existingClusterMap = KubeConfigTestHelpers.createClusterMap(data.clusterName, data.clusterUrl)
        val existingContextMap = KubeConfigTestHelpers.createContextMap(data.contextName, data.clusterName, data.userName)
        val config = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath, existingUserMap, existingClusterMap, existingContextMap)
        val allConfigs = listOf(config)
        val mockContext = setupUpdateExistingContextMocks(data.clusterName, data.userName, data.contextName, allConfigs, config, null)

        val update = KubeConfigUpdate.UpdateClientCert(
            data.clusterName,
            data.clusterUrl,
            data.newClientCertPem,
            data.newClientKeyPem,
            mockContext,
            allConfigs,
            testPersisterFactory,
        )

        // when
        update.apply()

        // then - token should be removed, only the new certificates should remain
        verify {
            persisterFor(kubeConfigPath).save(
                any(),
                any(),
                match { users ->
                    assertThat(users).hasSize(1)
                    verifyUserWithClientCert(
                        users[0] as Map<*, *>,
                        data.userName,
                        data.newClientCertPem,
                        data.newClientKeyPem
                    )
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply UpdateClientCert removes client-certificate and client-key file-path fields when setting client cert`() {
        // given
        val data = UpdateClientCertWithTokenTestData()
        val existingUserMap = KubeConfigTestHelpers.createUserMapWithClientCert(
            data.userName,
            data.oldToken,
            data.oldClientCertPem,
            data.oldClientKeyPem
        )
        val existingClusterMap = KubeConfigTestHelpers.createClusterMap(data.clusterName, data.clusterUrl)
        val existingContextMap = KubeConfigTestHelpers.createContextMap(data.contextName, data.clusterName, data.userName)
        val config = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath, existingUserMap, existingClusterMap, existingContextMap)
        val allConfigs = listOf(config)
        val mockContext = setupUpdateExistingContextMocks(data.clusterName, data.userName, data.contextName, allConfigs, config, null)

        val update = KubeConfigUpdate.UpdateClientCert(
            data.clusterName,
            data.clusterUrl,
            data.newClientCertPem,
            data.newClientKeyPem,
            mockContext,
            allConfigs,
            testPersisterFactory,
        )

        // when
        update.apply()

        // then - file-path cert fields should be removed, only data-path fields should remain
        verify {
            persisterFor(kubeConfigPath).save(
                any(),
                any(),
                match { users ->
                    assertThat(users).hasSize(1)
                    verifyUserWithClientCert(
                        users[0] as Map<*, *>,
                        data.userName,
                        data.newClientCertPem,
                        data.newClientKeyPem
                    )
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply UpdateToken sets current context on primary config when no config has current context`() {
        // given
        val data = UpdateTokenTestData()
        val (existingUserMap, existingClusterMap, existingContextMap) = createUpdateTokenTestMaps(data)
        val config = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath, existingUserMap, existingClusterMap, existingContextMap, "")
        val allConfigs = listOf(config)
        val mockContext = setupUpdateExistingContextMocks(data.clusterName, data.userName, data.contextName, allConfigs, config, null)

        val update = KubeConfigUpdate.UpdateToken(data.clusterName, data.clusterUrl, data.newToken, mockContext, allConfigs, testPersisterFactory)

        // when
        update.apply()

        // then - token and current-context are persisted in a single save on the primary config
        verify(exactly = 1) {
            persisterFor(kubeConfigPath).save(any(), any(), any(), any(), any())
        }

        verify {
            persisterFor(kubeConfigPath).save(
                any(),
                any(),
                match { users ->
                    assertThat(users).hasSize(1)
                    verifyUser(users[0] as Map<*, *>, data.userName, data.newToken)
                },
                any(),
                match { currentContext ->
                    currentContext == data.contextName
                },
            )
        }
    }

    @Test
    fun `#apply UpdateToken removes client-certificate-data and client-key-data when setting a new token`() {
        // given
        val data = UpdateTokenWithClientCertTestData()
        val existingUserMap = KubeConfigTestHelpers.createUserMapWithClientCert(
            data.userName,
            data.oldToken,
            data.clientCertPem,
            data.clientKeyPem
        )
        val existingClusterMap = KubeConfigTestHelpers.createClusterMap(data.clusterName, data.clusterUrl)
        val existingContextMap = KubeConfigTestHelpers.createContextMap(data.contextName, data.clusterName, data.userName)
        val config = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath, existingUserMap, existingClusterMap, existingContextMap)
        val allConfigs = listOf(config)
        val mockContext = setupUpdateExistingContextMocks(data.clusterName, data.userName, data.contextName, allConfigs, config, null)

        val update = KubeConfigUpdate.UpdateToken(data.clusterName, data.clusterUrl, data.newToken, mockContext, allConfigs, testPersisterFactory)

        // when
        update.apply()

        // then - certificates should be removed, only the new token should remain
        verify {
            persisterFor(kubeConfigPath).save(
                any(),
                any(),
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
    fun `#apply UpdateToken removes client-certificate and client-key file-path fields when setting a new token`() {
        // given
        val data = UpdateTokenWithClientCertTestData()
        val existingUserMap = KubeConfigTestHelpers.createUserMapWithClientCert(
            data.userName,
            data.oldToken,
            data.clientCertPem,
            data.clientKeyPem
        )
        val existingClusterMap = KubeConfigTestHelpers.createClusterMap(data.clusterName, data.clusterUrl)
        val existingContextMap = KubeConfigTestHelpers.createContextMap(data.contextName, data.clusterName, data.userName)
        val config = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath, existingUserMap, existingClusterMap, existingContextMap)
        val allConfigs = listOf(config)
        val mockContext = setupUpdateExistingContextMocks(data.clusterName, data.userName, data.contextName, allConfigs, config, null)

        val update = KubeConfigUpdate.UpdateToken(data.clusterName, data.clusterUrl, data.newToken, mockContext, allConfigs, testPersisterFactory)

        // when
        update.apply()

        // then - both file-path and data-path cert fields should be removed, only token remains
        verify {
            persisterFor(kubeConfigPath).save(
                any(),
                any(),
                match { users ->
                    assertThat(users).hasSize(1)
                    verifyUserWithTokenOnly(users[0] as Map<*, *>, data.userName, data.newToken)
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply generates unique user name if user name already exists`() {
        // given
        val data = CreateContextTestData()
        val existingUserMap = KubeConfigTestHelpers.createUserMap(data.clusterName, "existing-token")

        val config = KubeConfigTestHelpers.createMockKubeConfig(
            kubeConfigPath,
            users = listOf(existingUserMap)
        )
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, kubeConfigPath)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs, testPersisterFactory)

        // when
        update.apply()

        // then
        verify {
            persisterFor(kubeConfigPath).save(
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
            kubeConfigPath,
            clusters = listOf(existingClusterMap)
        )
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, kubeConfigPath)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs, testPersisterFactory)

        // when
        update.apply()

        // then
        verify {
            persisterFor(kubeConfigPath).save(
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
            kubeConfigPath,
            contexts = listOf(existingContextMap)
        )
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, kubeConfigPath)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs, testPersisterFactory)

        // when
        update.apply()

        // then
        verify {
            persisterFor(kubeConfigPath).save(
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
            kubeConfigPath,
            users = listOf(existingUser1, existingUser2)
        )
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, kubeConfigPath)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs, testPersisterFactory)

        // when
        update.apply()

        // then
        verify {
            persisterFor(kubeConfigPath).save(
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
            kubeConfigPath,
            users = listOf(existingUserMap)
        )
        val config2 = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath)
        val allConfigs = listOf(config1, config2)
        setupCreateContextMocks(data.clusterName, allConfigs, kubeConfigPath)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs, testPersisterFactory)

        // when
        update.apply()

        // then - should generate unique name even though the duplicate is in a different config
        verify {
            persisterFor(kubeConfigPath).save(
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
            kubeConfigPath,
            contexts = listOf(existingContextMap),
            clusters = listOf(existingClusterMap),
            users = listOf(existingUserMap)
        )
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, kubeConfigPath)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs, testPersisterFactory)

        // when
        update.apply()

        // then - all three should have unique names with suffix 2
        verify {
            persisterFor(kubeConfigPath).save(
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
            kubeConfigPath,
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
        setupCreateContextMocks(data.clusterName, allConfigs, kubeConfigPath)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs, testPersisterFactory)
        
        // when
        update.apply()
        
        // then
        verify {
            persisterFor(kubeConfigPath).save(
                any(),
                any(),
                any(),
                any(),
                match { currentContext -> verifyCurrentContext(currentContext, expectedContextName) },
            )
        }
    }

    @Test
    fun `#apply addNewContext persists entries when config lists are null`() {
        // given
        val data = CreateContextTestData()
        val config = mockk<KubeConfig>(relaxed = true)
        every { config.users } returns null
        every { config.clusters } returns null
        every { config.contexts } returns null
        every { config.currentContext } answers { "" }
        every { config.path } returns kubeConfigPath
        every { config.preferences } returns mockk()
        every { config.setContext(any()) } returns true

        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, kubeConfigPath)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs, testPersisterFactory)

        // when
        update.apply()

        // then
        verify {
            persisterFor(kubeConfigPath).save(
                match { savedContexts ->
                    assertThat(savedContexts).hasSize(1)
                    verifyContext(
                        savedContexts[0] as Map<*, *>,
                        "${data.clusterName}/${data.clusterName}",
                        data.clusterName,
                        data.clusterName
                    )
                },
                match { savedClusters ->
                    assertThat(savedClusters).hasSize(1)
                    verifyCluster(savedClusters[0] as Map<*, *>, data.clusterName, data.clusterUrl)
                },
                match { savedUsers ->
                    assertThat(savedUsers).hasSize(1)
                    verifyUser(savedUsers[0] as Map<*, *>, data.clusterName, data.token)
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `#apply works when preferences are null`() {
        // given
        val data = CreateContextTestData()
        val config = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath)
        every { config.preferences } returns null
        
        val allConfigs = listOf(config)
        setupCreateContextMocks(data.clusterName, allConfigs, kubeConfigPath)

        val update = KubeConfigUpdate.CreateContext(data.clusterName, data.clusterUrl, data.token, allConfigs, testPersisterFactory)
        
        // when
        update.apply()
        
        // then
        verify {
            persisterFor(kubeConfigPath).save(
                any(),
                any(),
                any(),
                null,
                any(),
            )
        }
    }

    @Test
    fun `#apply updates token when preferences are null`() {
        // given
        val data = UpdateTokenTestData()
        val (existingUserMap, existingClusterMap, existingContextMap) = createUpdateTokenTestMaps(data)
        val config = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath, existingUserMap, existingClusterMap, existingContextMap)
        every { config.preferences } returns null
        
        val allConfigs = listOf(config)
        val mockContext = setupUpdateExistingContextMocks(data.clusterName, data.userName, data.contextName, allConfigs, config, null)

        val update = KubeConfigUpdate.UpdateToken(data.clusterName, data.clusterUrl, data.newToken, mockContext, allConfigs, testPersisterFactory)

        // when
        update.apply()

        // then
        verify {
            persisterFor(kubeConfigPath).save(
                any(),
                any(),
                any(),
                null,
                any()
            )
        }
    }

    @Test
    fun `#apply UpdateClientCert updates client cert when preferences are null`() {
        // given
        val data = UpdateClientCertTestData()
        val (existingUserMap, existingClusterMap, existingContextMap) = createUpdateClientCertTestMaps(data)
        val config = KubeConfigTestHelpers.createMockKubeConfig(kubeConfigPath, existingUserMap, existingClusterMap, existingContextMap)
        every { config.preferences } returns null

        val allConfigs = listOf(config)
        val mockContext = setupUpdateExistingContextMocks(data.clusterName, data.userName, data.contextName, allConfigs, config, null)

        val update = KubeConfigUpdate.UpdateClientCert(
            data.clusterName,
            data.clusterUrl,
            data.clientCertPem,
            data.clientKeyPem,
            mockContext,
            allConfigs,
            testPersisterFactory,
        )

        // when
        update.apply()

        // then
        verify {
            persisterFor(kubeConfigPath).save(
                any(),
                any(),
                any(),
                null,
                any()
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

    private data class CreateContextWithClientCertTestData(
        val clusterName: String = "death-star",
        val clusterUrl: String = "https://death-star.com",
        val clientCertPem: String = "empire-client-cert",
        val clientKeyPem: String = "empire-client-key"
    )

    private data class UpdateClientCertTestData(
        val oldToken: String = "use-the-force",
        val clientCertPem: String = "rebel-client-cert",
        val clientKeyPem: String = "rebel-client-key",
        val clusterName: String = "yavin-4",
        val clusterUrl: String = "https://yavin-4.com",
        val userName: String = "leia-organa",
        val contextName: String = clusterName
    )

    private data class UpdateTokenWithClientCertTestData(
        val oldToken: String = "old-token",
        val newToken: String = "new-token",
        val clientCertPem: String = "existing-cert",
        val clientKeyPem: String = "existing-key",
        val clusterName: String = "endor",
        val clusterUrl: String = "https://endor.com",
        val userName: String = "lando-calrissian",
        val contextName: String = clusterName
    )

    private data class UpdateClientCertWithTokenTestData(
        val oldToken: String = "old-token",
        val oldClientCertPem: String = "old-client-cert",
        val oldClientKeyPem: String = "old-client-key",
        val newClientCertPem: String = "new-client-cert",
        val newClientKeyPem: String = "new-client-key",
        val clusterName: String = "hoth",
        val clusterUrl: String = "https://hoth.com",
        val userName: String = "han-solo",
        val contextName: String = clusterName
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

    private fun verifyUserWithClientCert(
        user: Map<*, *>,
        expectedName: String,
        expectedCertPem: String,
        expectedKeyPem: String
    ): Boolean {
        val name = Utils.getValue(user, arrayOf("name")) as String
        val cert = Utils.getValue(user, arrayOf("user", "client-certificate-data")) as String
        val key = Utils.getValue(user, arrayOf("user", "client-key-data")) as String
        assertThat(name).isEqualTo(expectedName)
        assertThat(cert).isEqualTo(PemUtils.toBase64(expectedCertPem))
        assertThat(key).isEqualTo(PemUtils.toBase64(expectedKeyPem))
        assertThat(Utils.getValue(user, arrayOf("user", "token"))).isNull()
        assertThat(Utils.getValue(user, arrayOf("user", "client-certificate"))).isNull()
        assertThat(Utils.getValue(user, arrayOf("user", "client-key"))).isNull()
        return true
    }

    private fun verifyUserWithTokenOnly(
        user: Map<*, *>,
        expectedName: String,
        expectedToken: String
    ): Boolean {
        val name = Utils.getValue(user, arrayOf("name")) as String
        val token = Utils.getValue(user, arrayOf("user", "token")) as String
        assertThat(name).isEqualTo(expectedName)
        assertThat(token).isEqualTo(expectedToken)
        assertThat(Utils.getValue(user, arrayOf("user", "client-certificate"))).isNull()
        assertThat(Utils.getValue(user, arrayOf("user", "client-key"))).isNull()
        assertThat(Utils.getValue(user, arrayOf("user", "client-certificate-data"))).isNull()
        assertThat(Utils.getValue(user, arrayOf("user", "client-key-data"))).isNull()
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

    private fun createUpdateClientCertTestMaps(data: UpdateClientCertTestData): Triple<MutableMap<String, Any>, MutableMap<String, Any>, MutableMap<String, Any>> {
        val existingUserMap = KubeConfigTestHelpers.createUserMap(data.userName, data.oldToken)
        val existingClusterMap = KubeConfigTestHelpers.createClusterMap(data.clusterName, data.clusterUrl)
        val existingContextMap = KubeConfigTestHelpers.createContextMap(data.contextName, data.clusterName, data.userName)
        return Triple(existingUserMap, existingClusterMap, existingContextMap)
    }

    private fun setupUpdateExistingContextMocks(
        clusterName: String,
        userName: String,
        contextName: String,
        allConfigs: List<KubeConfig>,
        configForUser: KubeConfig,
        configForCurrentContext: KubeConfig?
    ): KubeConfigNamedContext {
        mockkObject(KubeConfigNamedContext)
        val mockContext = mockk<KubeConfigNamedContext>(relaxed = true)
        every { mockContext.context } returns KubeConfigContext(userName, clusterName)
        every { mockContext.name } returns contextName
        every { KubeConfigNamedContext.getByClusterName(clusterName, allConfigs) } returns mockContext
        every { KubeConfigUtils.getAllConfigs(any()) } returns allConfigs
        val configFiles = allConfigs.mapNotNull { it.path }.distinct()
        every { KubeConfigUtils.getAllConfigFiles() } returns configFiles
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
