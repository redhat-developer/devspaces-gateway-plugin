package com.redhat.devtools.gateway.kubeconfig

import io.kubernetes.client.util.KubeConfig
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileReader

class KubeConfigWriterTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `should find user in single kubeconfig file`() {
        // given
        val user = "luke-skywalker"
        val kubeConfigFile = File(tempDir, "jedi-config")
        kubeConfigFile.writeText("""
            apiVersion: v1
            clusters: []
            contexts: []
            current-context: ""
            kind: Config
            preferences: {}
            users:
            - name: $user
              user:
                token: death-star-plans
        """.trimIndent())

        // when
        val result = KubeConfigWriter.findKubeConfigFileForUser(user, kubeConfigFile.absolutePath)

        // then
        assertThat(result).isEqualTo(kubeConfigFile.absolutePath)
    }

    @Test
    fun `should find user in one of multiple kubeconfig files`() {
        // given
        val user = "han-solo"
        val kubeConfigFile1 = File(tempDir, "rebel-config")
        kubeConfigFile1.writeText("""
            users:
            - name: chewbacca
              user:
                token: wookiee-roar
        """.trimIndent())

        val kubeConfigFile2 = File(tempDir, "smuggler-config")
        kubeConfigFile2.writeText("""
            users:
            - name: $user
              user:
                token: millennium-falcon
        """.trimIndent())

        val kubeConfigEnv = "${kubeConfigFile1.absolutePath}${File.pathSeparator}${kubeConfigFile2.absolutePath}"

        // when
        val result = KubeConfigWriter.findKubeConfigFileForUser(user, kubeConfigEnv)

        // then
        assertThat(result).isEqualTo(kubeConfigFile2.absolutePath)
    }

    @Test
    fun `should return null if user is not found`() {
        // given
        val user = "jar-jar-binks"
        val kubeConfigFile = File(tempDir, "gungan-config")
        kubeConfigFile.writeText("""
            users:
            - name: anakin-skywalker
              user:
                token: dark-side-destiny
        """.trimIndent())

        // when
        val result = KubeConfigWriter.findKubeConfigFileForUser(user, kubeConfigFile.absolutePath)

        // then
        assertThat(result).isNull()
    }

    @Test
    fun `should detect update change type`() {
        // given
        val serverUrl = "https://coruscant.republic:6443"
        val clusterName = "coruscant-cluster"
        val userName = "yoda"
        val contextName = "coruscant-context"

        val kubeConfig = KubeConfig()
        kubeConfig.clusters = arrayListOf(
            mapOf(
                "name" to clusterName,
                "cluster" to mapOf("server" to serverUrl)
            )
        )
        kubeConfig.contexts = arrayListOf(
            mapOf(
                "name" to contextName,
                "context" to mapOf("cluster" to clusterName, "user" to userName)
            )
        )
        kubeConfig.currentContext = contextName

        // when
        val result = KubeConfigWriter.detectChangeType(kubeConfig, serverUrl)

        // then
        assertThat(result).isInstanceOf(ChangeType.Update::class.java)
        val update = result as ChangeType.Update
        assertThat(update.existingClusterName).isEqualTo(clusterName)
        assertThat(update.existingUserName).isEqualTo(userName)
    }

    @Test
    fun `should detect create change type`() {
        // given
        val serverUrl = "https://hoth.rebellion:6443"
        val kubeConfig = KubeConfig()
        kubeConfig.clusters = arrayListOf()

        // when
        val result = KubeConfigWriter.detectChangeType(kubeConfig, serverUrl)

        // then
        assertThat(result).isInstanceOf(ChangeType.Create::class.java)
        val create = result as ChangeType.Create
        assertThat(create.newClusterName).isEqualTo("hoth-rebellion-6443")
        assertThat(create.newUserName).isEqualTo("hoth-rebellion-6443-user")
        assertThat(create.newContextName).isEqualTo("hoth-rebellion-6443-context")
    }

    @Test
    fun `#applyChanges does update existing user token`() {
        // given
        val serverUrl = "https://dagobah.swamp:6443"
        val clusterName = "dagobah-cluster"
        val userName = "master-yoda"
        val contextName = "dagobah-context"
        val oldToken = "old-swamp-token"
        val newToken = "new-force-token"

        val kubeConfig = KubeConfig()
        kubeConfig.clusters = arrayListOf(
            mapOf(
                "name" to clusterName,
                "cluster" to mapOf("server" to serverUrl)
            )
        )
        kubeConfig.contexts = arrayListOf(
            mapOf(
                "name" to contextName,
                "context" to mapOf("cluster" to clusterName, "user" to userName)
            )
        )
        kubeConfig.users = arrayListOf(
            mapOf(
                "name" to userName,
                "user" to mapOf("token" to oldToken)
            )
        )
        kubeConfig.currentContext = contextName

        val change = ChangeType.Update(clusterName, userName)

        // when
        val modifiedKubeConfig = KubeConfigWriter.applyChanges(kubeConfig, change, serverUrl, newToken)

        // then
        val updatedUser = (modifiedKubeConfig.users as ArrayList<Map<String, Any>>)
            .find { it["name"] == userName }
        assertThat((updatedUser?.get("user") as? Map<*, *>)?.get("token")).isEqualTo(newToken)
        assertThat(modifiedKubeConfig.currentContext).isEqualTo(contextName)
    }

    @Test
    fun `#applyChanges does create new cluster, user, context`() {
        // given
        val serverUrl = "https://kamino.cloners:6443"
        val newToken = "clone-army-token"
        val initialKubeConfig = KubeConfig()
        initialKubeConfig.clusters = arrayListOf()
        initialKubeConfig.users = arrayListOf()
        initialKubeConfig.contexts = arrayListOf()
        initialKubeConfig.currentContext = "old-kamino-context"

        val change = ChangeType.Create(
            newClusterName = "kamino-cloners-6443",
            newUserName = "kamino-cloners-6443-user",
            newContextName = "kamino-cloners-6443-context"
        )

        // when
        val modifiedKubeConfig = KubeConfigWriter.applyChanges(initialKubeConfig, change, serverUrl, newToken)

        // then
        assertThat(modifiedKubeConfig.clusters).hasSize(1)
        val newCluster = (modifiedKubeConfig.clusters as ArrayList<Map<String, Any>>).first()
        assertThat(newCluster["name"]).isEqualTo("kamino-cloners-6443")
        assertThat((newCluster["cluster"] as Map<*, *>)["server"]).isEqualTo(serverUrl)

        assertThat(modifiedKubeConfig.users).hasSize(1)
        val newUser = (modifiedKubeConfig.users as ArrayList<Map<String, Any>>).first()
        assertThat(newUser["name"]).isEqualTo("kamino-cloners-6443-user")
        assertThat((newUser["user"] as Map<*, *>)["token"]).isEqualTo(newToken)

        assertThat(modifiedKubeConfig.contexts).hasSize(1)
        val newContext = (modifiedKubeConfig.contexts as ArrayList<Map<String, Any>>).first()
        assertThat(newContext["name"]).isEqualTo("kamino-cloners-6443-context")
        assertThat((newContext["context"] as Map<*, *>)["cluster"]).isEqualTo("kamino-cloners-6443")
        assertThat((newContext["context"] as Map<*, *>)["user"]).isEqualTo("kamino-cloners-6443-user")

        assertThat(modifiedKubeConfig.currentContext).isEqualTo("kamino-cloners-6443-context")
    }

    @Test
    fun `#saveKubeConfigToFile does save modified kubeconfig to file`() {
        // given
        val filePath = File(tempDir, "star-wars-config").absolutePath
        val serverUrl = "https://mustafar.sith:6443"
        val userName = "darth-sidious"
        val token = "unlimited-power"

        // Initial empty config
        val initialKubeConfig = KubeConfig()
        initialKubeConfig.clusters = arrayListOf()
        initialKubeConfig.users = arrayListOf()
        initialKubeConfig.contexts = arrayListOf()

        // Apply changes to create a new context
        val change = ChangeType.Create(
            newClusterName = "mustafar-sith-6443",
            newUserName = userName,
            newContextName = "mustafar-context"
        )
        val modifiedKubeConfig = KubeConfigWriter.applyChanges(initialKubeConfig, change, serverUrl, token)

        // when
        runBlocking {
            KubeConfigWriter.saveKubeConfigToFile(modifiedKubeConfig, filePath)
        }

        // then
        val savedFileContent = File(filePath).readText()
        val reloadedKubeConfig = KubeConfig.loadKubeConfig(savedFileContent.byteInputStream())

        assertThat(reloadedKubeConfig.currentContext).isEqualTo("mustafar-context")
        val reloadedUsers = KubeConfigNamedUser.fromKubeConfig(reloadedKubeConfig) // Assuming KubeConfigNamedUser.fromKubeConfig exists
        val sidiousUser = reloadedUsers.find { it.name == userName }
        assertThat(sidiousUser?.user?.token).isEqualTo(token)
    }