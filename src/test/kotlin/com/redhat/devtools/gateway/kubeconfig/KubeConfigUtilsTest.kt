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
import com.redhat.devtools.gateway.openshift.Cluster
import io.kubernetes.client.util.KubeConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText


class KubeConfigUtilsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `#getClusters returns clusters when given multiple kubeconfig files`() {
        // given
        val kubeConfigFile1 = createTempKubeConfigFile(
            "config1", """
            apiVersion: v1
            clusters:
            - cluster:
                server: https://api.tatooine.starwars.com:6443
              name: tatooine-cluster
            contexts:
            - context:
                cluster: tatooine-cluster
                user: luke-skywalker
              name: tatooine-cluster
            current-context: tatooine-cluster
            kind: Config
            preferences: {}
            users:
            - name: luke-skywalker
              user:
                token: jedi-token
        """.trimIndent()
        )
        val kubeConfigFile2 = createTempKubeConfigFile(
            "config2", """
            apiVersion: v1
            clusters:
            - cluster:
                server: https://api.dagobah.starwars.com:6443
              name: dagobah-cluster
            contexts:
            - context:
                cluster: dagobah-cluster
                user: yoda-master
              name: dagobah-cluster
            current-context: dagobah-cluster
            kind: Config
            preferences: {}
            users:
            - name: yoda-master
              user:
                token: force-token
        """.trimIndent()
        )

        // when
        val clusters = KubeConfigUtils.getClusters(listOf(kubeConfigFile1, kubeConfigFile2))

        // then
        assertThat(clusters).containsExactlyInAnyOrder(
            Cluster(
                name = "tatooine-cluster",
                url = "https://api.tatooine.starwars.com:6443",
                token = "jedi-token"
            ),
            Cluster(
                name = "dagobah-cluster",
                url = "https://api.dagobah.starwars.com:6443",
                token = "force-token"
            )
        )
    }

    @Test
    fun `#getClusters returns no cluster when given non-existent file`() {
        // given
        val nonExistentFile = tempDir.resolve("non-existent-file")

        // when
        val clusters = KubeConfigUtils.getClusters(listOf(nonExistentFile))

        // then
        assertThat(clusters).isEmpty()
    }

    @Test
    fun `#getClusters returns no clusters when given empty file`() {
        // given
        val emptyFile = createTempKubeConfigFile("empty-config", "")

        // when
        val clusters = KubeConfigUtils.getClusters(listOf(emptyFile))

        // then
        assertThat(clusters).isEmpty()
    }

    @Test
    fun `#getClusters returns no cluster when given invalid yaml`() {
        // given
        val invalidFile = createTempKubeConfigFile("invalid-config", "this is not yaml")

        // when
        val clusters = KubeConfigUtils.getClusters(listOf(invalidFile))

        // then
        assertThat(clusters).isEmpty()
    }

    @Test
    fun `#getClusters returns cluster when given one valid and one invalid file`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config", """
            apiVersion: v1
            clusters:
            - cluster:
                server: https://api.endor.starwars.com:6443
              name: endor-cluster
            contexts:
            - context:
                cluster: endor-cluster
                user: leia-organa
              name: endor-cluster
            current-context: endor-cluster
            kind: Config
            preferences: {}
            users:
            - name: leia-organa
              user:
                token: rebel-token
        """.trimIndent()
        )
        val invalidFile = createTempKubeConfigFile("invalid-config", "this is not yaml")

        // when
        val clusters = KubeConfigUtils.getClusters(listOf(kubeConfigFile, invalidFile))

        // then
        assertThat(clusters).containsExactly(
            Cluster(
                name = "endor-cluster",
                url = "https://api.endor.starwars.com:6443",
                token = "rebel-token"
            )
        )
    }

    @Test
    fun `#isCurrentUserTokenAuth returns true if current user has token`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config",
            """
            apiVersion: v1
            clusters:
            - name: hoth-cluster
              cluster:
                server: https://api.hoth.starwars.com:6443
            users:
            - name: han-solo
              user:
                token: smuggler-token
            contexts:
            - name: echo-base
              context:
                cluster: hoth-cluster
                user: han-solo
            current-context: echo-base
        """.trimIndent()
        )
        val kubeConfig = KubeConfig.loadKubeConfig(kubeConfigFile.toFile().reader())
        kubeConfig.setContext("echo-base")

        // when
        val isTokenAuth = KubeConfigUtils.isCurrentUserTokenAuth(kubeConfig)

        // then
        assertThat(isTokenAuth).isTrue
    }

    @Test
    fun `#isCurrentUserTokenAuth returns false if current user has no token`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config",
            """
            apiVersion: v1
            clusters:
            - name: naboo-cluster
              cluster:
                server: https://api.naboo.starwars.com:6443
            users:
            - name: padme-amidala
              user:
                client-key-data: data
            contexts:
            - name: theed-city
              context:
                cluster: naboo-cluster
                user: padme-amidala
            current-context: theed-city
        """.trimIndent()
        )
        val kubeConfig = KubeConfig.loadKubeConfig(kubeConfigFile.toFile().reader())
        kubeConfig.setContext("theed-city")

        // when
        val isTokenAuth = KubeConfigUtils.isCurrentUserTokenAuth(kubeConfig)

        // then
        assertThat(isTokenAuth).isFalse
    }

    @Test
    fun `#getAllConfigs returns default config if KUBECONFIG is not set`() {
        // given
        val originalUserHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", tempDir.toString())
            val defaultKubeConfig = createTempKubeConfigFile(".kube/config", "default config")

            // when
            val allConfigs = KubeConfigUtils.getAllConfigFiles("")

            // then
            assertThat(allConfigs).containsExactly(defaultKubeConfig)
        } finally {
            System.setProperty("user.home", originalUserHome)
        }
    }

    @Test
    fun `#getAllConfigs returns configFiles from KUBECONFIG env var`() {
        // given
        val kubeConfigFile1 = createTempKubeConfigFile("config1", "content1")
        val kubeConfigFile2 = createTempKubeConfigFile("config2", "content2")
        val kubeconfigEnv = "${kubeConfigFile1}${File.pathSeparator}${kubeConfigFile2}"

        // when
        val allConfigs = KubeConfigUtils.getAllConfigFiles(kubeconfigEnv)

        // then
        assertThat(allConfigs).containsExactly(kubeConfigFile1, kubeConfigFile2)
    }


    @Test
    fun `#toString returns multiple kubeconfig files merged into a string`() {
        // given
        val kubeConfigFile1 = createTempKubeConfigFile("config1", "content1")
        val kubeConfigFile2 = createTempKubeConfigFile("config2", "content2")

        // when
        val merged = KubeConfigUtils.toString(listOf(kubeConfigFile1, kubeConfigFile2))

        // then
        assertThat(merged).isEqualTo("content1\n---\ncontent2")
    }

    @Test
    fun `#getCurrentContext returns current context`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config",
            """
            apiVersion: v1
            contexts:
            - context:
                cluster: geonosis-cluster
                user: jango-fett
              name: arena
            current-context: arena
        """.trimIndent()
        )
        val kubeConfig = KubeConfig.loadKubeConfig(kubeConfigFile.toFile().reader())
        kubeConfig.setContext("arena")

        // when
        val currentContext = KubeConfigUtils.getCurrentContext(kubeConfig)

        // then
        assertThat(currentContext?.name).isEqualTo("arena")
        assertThat(currentContext?.context?.cluster).isEqualTo("geonosis-cluster")
        assertThat(currentContext?.context?.user).isEqualTo("jango-fett")
    }

    @Test
    fun `#getCurrentClusterName returns null when current context is not set`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config", """
            apiVersion: v1
            clusters:
            - name: jakku-cluster
              cluster:
                server: https://api.jakku.starwars.com:6443
            users:
            - name: rey-scavenger
              user:
                token: force-sensitive-token
            contexts:
            - context:
                cluster: jakku-cluster
                user: rey-scavenger
              name: niima-outpost
        """.trimIndent()
        )
        val kubeconfigEnv = kubeConfigFile.toString()

        // when
        val clusterName = KubeConfigUtils.getCurrentClusterName(kubeconfigEnv)

        // then
        assertThat(clusterName).isNull()
    }

    @Test
    fun `#getCurrentClusterName returns name of cluster set in current context`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config", """
            apiVersion: v1
            clusters:
            - name: bespin-cluster
              cluster:
                server: https://api.bespin.starwars.com:6443
            users:
            - name: lando-calrissian
              user:
                token: cloud-city-token
            contexts:
            - context:
                cluster: bespin-cluster
                user: lando-calrissian
              name: cloud-city
            current-context: cloud-city
        """.trimIndent()
        )
        val kubeconfigEnv = kubeConfigFile.toString()

        // when
        val clusterName = KubeConfigUtils.getCurrentClusterName(kubeconfigEnv)

        // then
        assertThat(clusterName).isEqualTo("bespin-cluster")
    }

    @Test
    fun `#getAllConfigs returns configs with path set`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config", """
            apiVersion: v1
            clusters:
            - name: coruscant-cluster
              cluster:
                server: https://api.coruscant.starwars.com:6443
            users:
            - name: mace-windu
              user:
                token: jedi-council-token
            contexts:
            - context:
                cluster: coruscant-cluster
                user: mace-windu
              name: jedi-temple
            current-context: jedi-temple
        """.trimIndent()
        )

        // when
        val configs = KubeConfigUtils.getAllConfigs(listOf(kubeConfigFile))

        // then
        assertThat(configs).hasSize(1)
        assertThat(configs[0].path).isEqualTo(kubeConfigFile)
    }

    @Test
    fun `#getAllConfigs returns multiple configs from file with multiple documents`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config", """
            apiVersion: v1
            clusters:
            - name: mustafar-cluster
              cluster:
                server: https://api.mustafar.starwars.com:6443
            ---
            apiVersion: v1
            clusters:
            - name: kamino-cluster
              cluster:
                server: https://api.kamino.starwars.com:6443
        """.trimIndent()
        )

        // when
        val configs = KubeConfigUtils.getAllConfigs(listOf(kubeConfigFile))

        // then
        assertThat(configs).hasSize(2)
        assertThat(configs[0].path).isEqualTo(kubeConfigFile)
        assertThat(configs[1].path).isEqualTo(kubeConfigFile)
    }

    @Test
    fun `#getAllConfigs returns empty list when file contains invalid documents`() {
        // given
        val invalidFile = createTempKubeConfigFile("invalid-config", "not valid yaml")

        // when
        val configs = KubeConfigUtils.getAllConfigs(listOf(invalidFile))

        // then
        assertThat(configs).isEmpty()
    }

    @Test
    fun `#getConfigByUser returns config containing the user`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config", """
            apiVersion: v1
            clusters:
            - name: alderaan-cluster
              cluster:
                server: https://api.alderaan.starwars.com:6443
            users:
            - name: bail-organa
              user:
                token: senator-token
            contexts:
            - context:
                cluster: alderaan-cluster
                user: bail-organa
              name: alderaan-context
        """.trimIndent()
        )
        val configs = KubeConfigUtils.getAllConfigs(listOf(kubeConfigFile))
        val context = KubeConfigNamedContext(
            KubeConfigContext("bail-organa", "alderaan-cluster")
        )

        // when
        val config = KubeConfigUtils.getConfigByUser(context, configs)

        // then
        assertThat(config).isNotNull
        assertThat(config).isEqualTo(configs[0])
    }

    @Test
    fun `#getConfigByUser returns null when user not found`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config", """
            apiVersion: v1
            clusters:
            - name: dantooine-cluster
              cluster:
                server: https://api.dantooine.starwars.com:6443
            users:
            - name: obi-wan-kenobi
              user:
                token: old-ben-token
            contexts:
            - context:
                cluster: dantooine-cluster
                user: obi-wan-kenobi
              name: dantooine-context
        """.trimIndent()
        )
        val configs = KubeConfigUtils.getAllConfigs(listOf(kubeConfigFile))
        val context = KubeConfigNamedContext(
            KubeConfigContext("unknown-user", "dantooine-cluster")
        )

        // when
        val config = KubeConfigUtils.getConfigByUser(context, configs)

        // then
        assertThat(config).isNull()
    }

    @Test
    fun `#getConfigWithCurrentContext returns first config with current context`() {
        // given
        val kubeConfigFile1 = createTempKubeConfigFile(
            "config1", """
            apiVersion: v1
            clusters:
            - name: tatooine-cluster
              cluster:
                server: https://api.tatooine.starwars.com:6443
            users:
            - name: luke-skywalker
              user:
                token: jedi-token
            contexts:
            - context:
                cluster: tatooine-cluster
                user: luke-skywalker
              name: tatooine-context
            current-context: tatooine-context
        """.trimIndent()
        )
        val kubeConfigFile2 = createTempKubeConfigFile(
            "config2", """
            apiVersion: v1
            clusters:
            - name: dagobah-cluster
              cluster:
                server: https://api.dagobah.starwars.com:6443
            users:
            - name: yoda-master
              user:
                token: force-token
            contexts:
            - context:
                cluster: dagobah-cluster
                user: yoda-master
              name: dagobah-context
            current-context: dagobah-context
        """.trimIndent()
        )
        val configs = KubeConfigUtils.getAllConfigs(listOf(kubeConfigFile1, kubeConfigFile2))

        // when
        val config = KubeConfigUtils.getConfigWithCurrentContext(configs)

        // then
        assertThat(config).isNotNull
        assertThat(config).isEqualTo(configs[0])
        assertThat(config?.currentContext).isEqualTo("tatooine-context")
    }

    @Test
    fun `#getConfigWithCurrentContext returns null when no configs have current context`() {
        // given
        val kubeConfigFile1 = createTempKubeConfigFile(
            "config1", """
            apiVersion: v1
            clusters:
            - name: tatooine-cluster
              cluster:
                server: https://api.tatooine.starwars.com:6443
            users:
            - name: luke-skywalker
              user:
                token: jedi-token
            contexts:
            - context:
                cluster: tatooine-cluster
                user: luke-skywalker
              name: tatooine-context
        """.trimIndent()
        )
        val kubeConfigFile2 = createTempKubeConfigFile(
            "config2", """
            apiVersion: v1
            clusters:
            - name: dagobah-cluster
              cluster:
                server: https://api.dagobah.starwars.com:6443
            users:
            - name: yoda-master
              user:
                token: force-token
            contexts:
            - context:
                cluster: dagobah-cluster
                user: yoda-master
              name: dagobah-context
        """.trimIndent()
        )
        val configs = KubeConfigUtils.getAllConfigs(listOf(kubeConfigFile1, kubeConfigFile2))

        // when
        val config = KubeConfigUtils.getConfigWithCurrentContext(configs)

        // then
        assertThat(config).isNull()
    }

    @Test
    fun `#getConfigWithCurrentContext returns null when all configs have empty current context`() {
        // given
        val kubeConfigFile1 = createTempKubeConfigFile(
            "config1", """
            apiVersion: v1
            clusters:
            - name: tatooine-cluster
              cluster:
                server: https://api.tatooine.starwars.com:6443
            users:
            - name: luke-skywalker
              user:
                token: jedi-token
            contexts:
            - context:
                cluster: tatooine-cluster
                user: luke-skywalker
              name: tatooine-context
            current-context: ""
        """.trimIndent()
        )
        val kubeConfigFile2 = createTempKubeConfigFile(
            "config2", """
            apiVersion: v1
            clusters:
            - name: dagobah-cluster
              cluster:
                server: https://api.dagobah.starwars.com:6443
            users:
            - name: yoda-master
              user:
                token: force-token
            contexts:
            - context:
                cluster: dagobah-cluster
                user: yoda-master
              name: dagobah-context
            current-context: ""
        """.trimIndent()
        )
        val configs = KubeConfigUtils.getAllConfigs(listOf(kubeConfigFile1, kubeConfigFile2))

        // when
        val config = KubeConfigUtils.getConfigWithCurrentContext(configs)

        // then
        assertThat(config).isNull()
    }

    @Test
    fun `#getConfigWithCurrentContext returns first config when multiple configs have current context`() {
        // given
        val kubeConfigFile1 = createTempKubeConfigFile(
            "config1", """
            apiVersion: v1
            clusters:
            - name: tatooine-cluster
              cluster:
                server: https://api.tatooine.starwars.com:6443
            users:
            - name: luke-skywalker
              user:
                token: jedi-token
            contexts:
            - context:
                cluster: tatooine-cluster
                user: luke-skywalker
              name: tatooine-context
            current-context: tatooine-context
        """.trimIndent()
        )
        val kubeConfigFile2 = createTempKubeConfigFile(
            "config2", """
            apiVersion: v1
            clusters:
            - name: dagobah-cluster
              cluster:
                server: https://api.dagobah.starwars.com:6443
            users:
            - name: yoda-master
              user:
                token: force-token
            contexts:
            - context:
                cluster: dagobah-cluster
                user: yoda-master
              name: dagobah-context
            current-context: dagobah-context
        """.trimIndent()
        )
        val kubeConfigFile3 = createTempKubeConfigFile(
            "config3", """
            apiVersion: v1
            clusters:
            - name: hoth-cluster
              cluster:
                server: https://api.hoth.starwars.com:6443
            users:
            - name: han-solo
              user:
                token: smuggler-token
            contexts:
            - context:
                cluster: hoth-cluster
                user: han-solo
              name: hoth-context
            current-context: hoth-context
        """.trimIndent()
        )
        val configs = KubeConfigUtils.getAllConfigs(listOf(kubeConfigFile1, kubeConfigFile2, kubeConfigFile3))

        // when
        val config = KubeConfigUtils.getConfigWithCurrentContext(configs)

        // then
        assertThat(config).isNotNull
        assertThat(config).isEqualTo(configs[0])
        assertThat(config?.currentContext).isEqualTo("tatooine-context")
    }

    @Test
    fun `#getConfigWithCurrentContext returns null when configs list is empty`() {
        // given
        val configs = emptyList<KubeConfig>()

        // when
        val config = KubeConfigUtils.getConfigWithCurrentContext(configs)

        // then
        assertThat(config).isNull()
    }

    @Test
    fun `#urlToName returns hostname with port when port is present`() {
        // given
        val url = "https://api.kashyyyk.starwars.com:8080"

        // when
        val name = KubeConfigUtils.urlToName(url)

        // then
        assertThat(name).isEqualTo("api.kashyyyk.starwars.com-8080")
    }

    @Test
    fun `#urlToName returns hostname without port when port is not present`() {
        // given
        val url = "https://api.kashyyyk.starwars.com"

        // when
        val name = KubeConfigUtils.urlToName(url)

        // then
        assertThat(name).isEqualTo("api.kashyyyk.starwars.com")
    }

    @Test
    fun `#urlToName returns null when host is null`() {
        // given
        val url = "file:///path/to/file"

        // when
        val name = KubeConfigUtils.urlToName(url)

        // then
        assertThat(name).isNull()
    }

    @Test
    fun `#urlToName returns null for malformed URL`() {
        // given
        val url = "ht@tp://api.example.com"

        // when
        val name = KubeConfigUtils.urlToName(url)

        // then
        assertThat(name).isNull()
    }

    @Test
    fun `#sanitizeName converts to lowercase`() {
        // given
        val name = "DEATH-STAR"

        // when
        val sanitized = KubeConfigUtils.sanitizeName(name)

        // then
        assertThat(sanitized).isEqualTo("death-star")
    }

    @Test
    fun `#sanitizeName replaces invalid characters with hyphens`() {
        // given
        val name = "death_star@cluster#123"

        // when
        val sanitized = KubeConfigUtils.sanitizeName(name)

        // then
        assertThat(sanitized).isEqualTo("death-star-cluster-123")
    }

    @Test
    fun `#sanitizeName removes leading and trailing hyphens`() {
        // given
        val name = "---death-star---"

        // when
        val sanitized = KubeConfigUtils.sanitizeName(name)

        // then
        assertThat(sanitized).isEqualTo("death-star")
    }

    @Test
    fun `#sanitizeName truncates names longer than 253 characters`() {
        // given
        val longName = "a".repeat(300)

        // when
        val sanitized = KubeConfigUtils.sanitizeName(longName)

        // then
        assertThat(sanitized).hasSize(253)
        assertThat(sanitized).isEqualTo("a".repeat(253))
    }

    @Test
    fun `#sanitizeName preserves periods and hyphens`() {
        // given
        val name = "death-star.cluster-name"

        // when
        val sanitized = KubeConfigUtils.sanitizeName(name)

        // then
        assertThat(sanitized).isEqualTo("death-star.cluster-name")
    }

    @Test
    fun `#toString returns null when given empty list`() {
        // given
        val emptyList = emptyList<Path>()

        // when
        val result = KubeConfigUtils.toString(emptyList)

        // then
        assertThat(result).isNull()
    }

    @Test
    fun `#toString returns null when files contain only whitespace`() {
        // given
        val emptyFile = createTempKubeConfigFile("empty-config", "   \n  \t  ")

        // when
        val result = KubeConfigUtils.toString(listOf(emptyFile))

        // then
        assertThat(result).isNull()
    }

    @Test
    fun `#toString handles files with multiple documents separated by ---`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config", """
            apiVersion: v1
            kind: Config
            ---
            apiVersion: v1
            kind: Config
        """.trimIndent()
        )

        // when
        val result = KubeConfigUtils.toString(listOf(kubeConfigFile))

        // then
        assertThat(result).contains("---")
        assertThat(result).contains("apiVersion: v1")
    }

    @Test
    fun `#getCurrentContext returns null when current context is not set`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config", """
            apiVersion: v1
            contexts:
            - context:
                cluster: felucia-cluster
                user: aayla-secura
              name: felucia-context
        """.trimIndent()
        )
        val kubeConfig = KubeConfig.loadKubeConfig(kubeConfigFile.toFile().reader())

        // when
        val currentContext = KubeConfigUtils.getCurrentContext(kubeConfig)

        // then
        assertThat(currentContext).isNull()
    }

    @Test
    fun `#getCurrentContext returns null when context name does not match`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config", """
            apiVersion: v1
            contexts:
            - context:
                cluster: utapau-cluster
                user: ki-adi-mundi
              name: utapau-context
            current-context: non-existent-context
        """.trimIndent()
        )
        val kubeConfig = KubeConfig.loadKubeConfig(kubeConfigFile.toFile().reader())
        kubeConfig.setContext("non-existent-context")

        // when
        val currentContext = KubeConfigUtils.getCurrentContext(kubeConfig)

        // then
        assertThat(currentContext).isNull()
    }

    @Test
    fun `#getAllConfigFiles returns default config when env var is empty string`() {
        // given
        val originalUserHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", tempDir.toString())
            val defaultKubeConfig = createTempKubeConfigFile(".kube/config", "default config")
            val emptyEnv = ""

            // when
            val configs = KubeConfigUtils.getAllConfigFiles(emptyEnv)

            // then
            assertThat(configs).containsExactly(defaultKubeConfig)
        } finally {
            System.setProperty("user.home", originalUserHome)
        }
    }

    @Test
    fun `#getAllConfigFiles filters out non-existent files from env var`() {
        // given
        val existingFile = createTempKubeConfigFile("existing-config", "content")
        val nonExistentFile = tempDir.resolve("non-existent-config")
        val kubeconfigEnv = "${existingFile}${File.pathSeparator}${nonExistentFile}"

        // when
        val configs = KubeConfigUtils.getAllConfigFiles(kubeconfigEnv)

        // then
        assertThat(configs).containsExactly(existingFile)
    }

    @Test
    fun `#path extension property sets and gets path correctly`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config", """
            apiVersion: v1
            kind: Config
        """.trimIndent()
        )
        val kubeConfig = KubeConfig.loadKubeConfig(kubeConfigFile.toFile().reader())

        // when
        kubeConfig.path = kubeConfigFile

        // then
        assertThat(kubeConfig.path).isEqualTo(kubeConfigFile)
    }

    @Test
    fun `#path extension property returns null when not set`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config", """
            apiVersion: v1
            kind: Config
        """.trimIndent()
        )
        val kubeConfig = KubeConfig.loadKubeConfig(kubeConfigFile.toFile().reader())

        // when
        val path = kubeConfig.path

        // then
        assertThat(path).isNull()
    }

    @Test
    fun `#path extension property removes path when set to null`() {
        // given
        val kubeConfigFile = createTempKubeConfigFile(
            "config", """
            apiVersion: v1
            kind: Config
        """.trimIndent()
        )
        val kubeConfig = KubeConfig.loadKubeConfig(kubeConfigFile.toFile().reader())
        kubeConfig.path = kubeConfigFile

        // when
        kubeConfig.path = null

        // then
        assertThat(kubeConfig.path).isNull()
    }

    private fun createTempKubeConfigFile(name: String, content: String): Path {
        val file = tempDir.resolve(name)
        file.parent.createDirectories()
        file.createFile()
        file.writeText(content)
        return file
    }
}
