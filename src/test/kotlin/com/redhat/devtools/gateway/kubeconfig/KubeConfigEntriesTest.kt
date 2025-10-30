package com.redhat.devtools.gateway.kubeconfig

import io.kubernetes.client.util.KubeConfig
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class KubeConfigEntriesTest {

    @Nested
    inner class KubeConfigClusterTest {

        @Test
        fun `#fromMap is parsing cluster with all fields`() {
            // given
            val map = mapOf(
                "server" to "https://api.example.com:6443",
                "certificate-authority-data" to "LS0tLS1CRUdJTi...",
                "insecure-skip-tls-verify" to true
            )

            // when
            val cluster = KubeConfigCluster.fromMap(map)

            // then
            Assertions.assertThat(cluster).isNotNull
            Assertions.assertThat(cluster?.server).isEqualTo("https://api.example.com:6443")
            Assertions.assertThat(cluster?.certificateAuthorityData).isEqualTo("LS0tLS1CRUdJTi...")
            Assertions.assertThat(cluster?.insecureSkipTlsVerify).isTrue()
        }

        @Test
        fun `#fromMap is parsing cluster with only server`() {
            // given
            val map = mapOf(
                "server" to "https://api.example.com:6443"
            )

            // when
            val cluster = KubeConfigCluster.fromMap(map)

            // then
            Assertions.assertThat(cluster).isNotNull
            Assertions.assertThat(cluster?.server).isEqualTo("https://api.example.com:6443")
            Assertions.assertThat(cluster?.certificateAuthorityData).isNull()
            Assertions.assertThat(cluster?.insecureSkipTlsVerify).isNull()
        }

        @Test
        fun `#fromMap returns null when server is missing`() {
            // given
            val map = mapOf(
                "certificate-authority-data" to "LS0tLS1CRUdJTi..."
            )

            // when
            val cluster = KubeConfigCluster.fromMap(map)

            // then
            Assertions.assertThat(cluster).isNull()
        }

        @Test
        fun `#fromMap returns null for empty map`() {
            // given
            // empty map

            // when
            val cluster = KubeConfigCluster.fromMap(emptyMap<String, Any>())

            // then
            Assertions.assertThat(cluster).isNull()
        }

        @Test
        fun `#fromMap is handling non-string server value gracefully`() {
            // given
            val map = mapOf(
                "server" to 12345  // non-string value
            )

            // when
            val cluster = KubeConfigCluster.fromMap(map)

            // then
            Assertions.assertThat(cluster).isNull()
        }

        @Test
        fun `#fromMap handles non-boolean insecure-skip-tls-verify value gracefully`() {
            // given
            val map = mapOf(
                "server" to "https://api.example.com:6443",
                "insecure-skip-tls-verify" to "not-a-boolean"
            )

            // when
            val cluster = KubeConfigCluster.fromMap(map)

            // then
            Assertions.assertThat(cluster).isNotNull
            Assertions.assertThat(cluster?.insecureSkipTlsVerify).isNull()
        }
    }

    @Nested
    inner class KubeConfigNamedClusterTest {

        @Test
        fun `#fromMap is parsing named cluster`() {
            // given
            val clusterObject = mapOf(
                "cluster" to mapOf(
                    "server" to "https://api.example.com:6443",
                    "certificate-authority-data" to "LS0tLS1CRUdJTi..."
                )
            )

            // when
            val namedCluster = KubeConfigNamedCluster.fromMap("my-cluster", clusterObject)

            // then
            Assertions.assertThat(namedCluster).isNotNull
            Assertions.assertThat(namedCluster?.name).isEqualTo("my-cluster")
            Assertions.assertThat(namedCluster?.cluster?.server).isEqualTo("https://api.example.com:6443")
        }

        @Test
        fun `#fromMap returns null when cluster details are invalid`() {
            // given
            val clusterObject = mapOf(
                "cluster" to mapOf(
                    "invalid" to "data"
                )
            )

            // when
            val namedCluster = KubeConfigNamedCluster.fromMap("my-cluster", clusterObject)

            // then
            Assertions.assertThat(namedCluster).isNull()
        }

        @Test
        fun `#fromMap returns null when cluster key is missing`() {
            // given
            val clusterObject = mapOf(
                "name" to "my-cluster"
            )

            // when
            val namedCluster = KubeConfigNamedCluster.fromMap("my-cluster", clusterObject)

            // then
            Assertions.assertThat(namedCluster).isNull()
        }

        @Test
        fun `#fromKubeConfig is parsing multiple clusters`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.clusters } returns arrayListOf<Any>(
                mapOf(
                    "name" to "skywalker",
                    "cluster" to mapOf("server" to "https://api1.example.com:6443")
                ),
                mapOf(
                    "name" to "darth-vader",
                    "cluster" to mapOf("server" to "https://api2.example.com:6443")
                )
            )

            // when
            val clusters = KubeConfigNamedCluster.fromKubeConfig(kubeConfig)

            // then
            Assertions.assertThat(clusters).hasSize(2)
            Assertions.assertThat(clusters[0].name).isEqualTo("skywalker")
            Assertions.assertThat(clusters[0].cluster.server).isEqualTo("https://api1.example.com:6443")
            Assertions.assertThat(clusters[1].name).isEqualTo("darth-vader")
            Assertions.assertThat(clusters[1].cluster.server).isEqualTo("https://api2.example.com:6443")
        }

        @Test
        fun `#fromKubeConfig skips invalid clusters`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.clusters } returns arrayListOf<Any>(
                mapOf(
                    "name" to "luke",
                    "cluster" to mapOf("server" to "https://api1.example.com:6443")
                ),
                mapOf(
                    "name" to "invalid-cluster"
                    // missing cluster details
                ),
                mapOf(
                    "name" to "leia",
                    "cluster" to mapOf("server" to "https://api2.example.com:6443")
                )
            )

            // when
            val clusters = KubeConfigNamedCluster.fromKubeConfig(kubeConfig)

            // then
            Assertions.assertThat(clusters).hasSize(2)
            Assertions.assertThat(clusters.map { it.name }).containsExactly("luke", "leia")
        }

        @Test
        fun `#fromKubeConfig returns empty list when clusters is null`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.clusters } returns null

            // when
            val clusters = KubeConfigNamedCluster.fromKubeConfig(kubeConfig)

            // then
            Assertions.assertThat(clusters).isEmpty()
        }

        @Test
        fun `#fromMap returns null when clusterObject is not a Map`() {
            // given
            // invalid clusterObject (string instead of map)

            // when
            val namedCluster = KubeConfigNamedCluster.fromMap("my-cluster", "not-a-map")

            // then
            Assertions.assertThat(namedCluster).isNull()
        }

        @Test
        fun `#fromMap returns null when clusterObject is null`() {
            // given
            // null clusterObject

            // when
            val namedCluster = KubeConfigNamedCluster.fromMap("my-cluster", null)

            // then
            Assertions.assertThat(namedCluster).isNull()
        }

        @Test
        fun `#fromKubeConfig is handling clusters with missing name`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.clusters } returns arrayListOf<Any>(
                mapOf(
                    "cluster" to mapOf("server" to "https://api1.example.com:6443")
                    // missing "name" field
                ),
                mapOf(
                    "name" to "darth-vader",
                    "cluster" to mapOf("server" to "https://api2.example.com:6443")
                )
            )

            // when
            val clusters = KubeConfigNamedCluster.fromKubeConfig(kubeConfig)

            // then
            Assertions.assertThat(clusters).hasSize(1)
            Assertions.assertThat(clusters[0].name).isEqualTo("darth-vader")
        }

        @Test
        fun `#fromKubeConfig is handling non-map cluster objects`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.clusters } returns arrayListOf<Any>(
                "not-a-map",  // invalid cluster object
                mapOf(
                    "name" to "skywalker",
                    "cluster" to mapOf("server" to "https://api1.example.com:6443")
                )
            )

            // when
            val clusters = KubeConfigNamedCluster.fromKubeConfig(kubeConfig)

            // then
            Assertions.assertThat(clusters).hasSize(1)
            Assertions.assertThat(clusters[0].name).isEqualTo("skywalker")
        }
    }

    @Nested
    inner class KubeConfigContextTest {

        @Test
        fun `#fromMap is parsing context with all fields`() {
            // given
            val map = mapOf(
                "cluster" to "my-cluster",
                "user" to "my-user",
                "namespace" to "my-namespace"
            )

            // when
            val context = KubeConfigContext.fromMap(map)

            // then
            Assertions.assertThat(context).isNotNull
            Assertions.assertThat(context?.cluster).isEqualTo("my-cluster")
            Assertions.assertThat(context?.user).isEqualTo("my-user")
            Assertions.assertThat(context?.namespace).isEqualTo("my-namespace")
        }

        @Test
        fun `#fromMap is parsing context without namespace`() {
            // given
            val map = mapOf(
                "cluster" to "my-cluster",
                "user" to "my-user"
            )

            // when
            val context = KubeConfigContext.fromMap(map)

            // then
            Assertions.assertThat(context).isNotNull
            Assertions.assertThat(context?.cluster).isEqualTo("my-cluster")
            Assertions.assertThat(context?.user).isEqualTo("my-user")
            Assertions.assertThat(context?.namespace).isNull()
        }

        @Test
        fun `#fromMap returns null when cluster is missing`() {
            // given
            val map = mapOf(
                "user" to "my-user"
            )

            // when
            val context = KubeConfigContext.fromMap(map)

            // then
            Assertions.assertThat(context).isNull()
        }

        @Test
        fun `#fromMap returns null when user is missing`() {
            // given
            val map = mapOf(
                "cluster" to "my-cluster"
            )

            // when
            val context = KubeConfigContext.fromMap(map)

            // then
            Assertions.assertThat(context).isNull()
        }

        @Test
        fun `#fromMap returns null when cluster is not a string`() {
            // given
            val map = mapOf(
                "cluster" to 12345,  // non-string value
                "user" to "my-user"
            )

            // when
            val context = KubeConfigContext.fromMap(map)

            // then
            Assertions.assertThat(context).isNull()
        }

        @Test
        fun `#fromMap returns null when user is not a string`() {
            // given
            val map = mapOf(
                "cluster" to "my-cluster",
                "user" to listOf("not", "a", "string")  // non-string value
            )

            // when
            val context = KubeConfigContext.fromMap(map)

            // then
            Assertions.assertThat(context).isNull()
        }

        @Test
        fun `#fromMap is handling handle non-string namespace gracefully`() {
            // given
            val map = mapOf(
                "cluster" to "my-cluster",
                "user" to "my-user",
                "namespace" to 42  // non-string namespace
            )

            // when
            val context = KubeConfigContext.fromMap(map)

            // then
            Assertions.assertThat(context).isNotNull
            Assertions.assertThat(context?.cluster).isEqualTo("my-cluster")
            Assertions.assertThat(context?.user).isEqualTo("my-user")
            Assertions.assertThat(context?.namespace).isNull()
        }
    }

    @Nested
    inner class KubeConfigNamedContextTest {

        @Test
        fun `#getKubeConfigNamedContext finds context for cluster`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.contexts } returns arrayListOf<Any>(
                mapOf(
                    "name" to "skywalker-context",
                    "context" to mapOf(
                        "cluster" to "skywalker-cluster",
                        "user" to "skywalker"
                    )
                ),
                mapOf(
                    "name" to "darth-vader-context",
                    "context" to mapOf(
                        "cluster" to "darth-vader-context",
                        "user" to "darth-vader"
                    )
                )
            )

            // when
            val namedContext = KubeConfigNamedContext.getByClusterName("skywalker-cluster", kubeConfig)

            // then
            Assertions.assertThat(namedContext).isNotNull
            Assertions.assertThat(namedContext?.name).isEqualTo("skywalker-context")
            Assertions.assertThat(namedContext?.context?.cluster).isEqualTo("skywalker-cluster")
            Assertions.assertThat(namedContext?.context?.user).isEqualTo("skywalker")
        }

        @Test
        fun `#getKubeConfigNamedContext returns null when cluster not found`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.contexts } returns arrayListOf<Any>(
                mapOf(
                    "name" to "skywalker-context",
                    "context" to mapOf(
                        "cluster" to "skywalker-cluster",
                        "user" to "skywalker"
                    )
                )
            )

            val namedContext = KubeConfigNamedContext.getByClusterName("nonexistent", kubeConfig)

            Assertions.assertThat(namedContext).isNull()
        }

        @Test
        fun `#getKubeConfigNamedContext returns null when contexts is null`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.contexts } returns null

            val namedContext = KubeConfigNamedContext.getByClusterName("skywalker", kubeConfig)

            Assertions.assertThat(namedContext).isNull()
        }

        @Test
        fun `#getKubeConfigNamedContext is handling contexts with missing context details`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.contexts } returns arrayListOf<Any>(
                mapOf(
                    "name" to "skywalker-context"
                    // missing "context" field
                ),
                mapOf(
                    "name" to "darth-vader-context",
                    "context" to mapOf(
                        "cluster" to "darth-vader-cluster",
                        "user" to "darth-vader"
                    )
                )
            )

            val namedContext = KubeConfigNamedContext.getByClusterName("darth-vader-cluster", kubeConfig)

            Assertions.assertThat(namedContext).isNotNull
            Assertions.assertThat(namedContext?.name).isEqualTo("darth-vader-context")
        }

        @Test
        fun `#getKubeConfigNamedContext is handling non-map context objects`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.contexts } returns arrayListOf<Any>(
                "not-a-map",  // invalid context object
                mapOf(
                    "name" to "skywalker-context",
                    "context" to mapOf(
                        "cluster" to "skywalker-cluster",
                        "user" to "skywalker"
                    )
                )
            )

            val namedContext = KubeConfigNamedContext.getByClusterName("skywalker-cluster", kubeConfig)

            Assertions.assertThat(namedContext).isNotNull
            Assertions.assertThat(namedContext?.name).isEqualTo("skywalker-context")
        }

        @Test
        fun `#getKubeConfigNamedContext is handling contexts with missing names`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.contexts } returns arrayListOf<Any>(
                mapOf(
                    "context" to mapOf(
                        "cluster" to "skywalker-cluster",
                        "user" to "skywalker"
                    )
                    // missing "name" field
                ),
                mapOf(
                    "name" to "darth-vader-context",
                    "context" to mapOf(
                        "cluster" to "darth-vader-cluster",
                        "user" to "darth-vader"
                    )
                )
            )

            val namedContext = KubeConfigNamedContext.getByClusterName("darth-vader-cluster", kubeConfig)

            Assertions.assertThat(namedContext).isNotNull
            Assertions.assertThat(namedContext?.name).isEqualTo("darth-vader-context")
        }
    }

    @Nested
    inner class KubeConfigUserTest {

        @Test
        fun `#fromMap is parsing user with token`() {
            // given
            val map = mapOf(
                "token" to "my-secret-token"
            )

            // when
            val user = KubeConfigUser.fromMap(map)

            // then
            Assertions.assertThat(user.token).isEqualTo("my-secret-token")
            Assertions.assertThat(user.clientCertificateData).isNull()
            Assertions.assertThat(user.clientKeyData).isNull()
            Assertions.assertThat(user.username).isNull()
            Assertions.assertThat(user.password).isNull()
        }

        @Test
        fun `#fromMap is parsing user with all fields`() {
            // given
            val map = mapOf(
                "token" to "my-secret-token",
                "client-certificate-data" to "cert-data",
                "client-key-data" to "key-data",
                "username" to "admin",
                "password" to "secret"
            )

            // when
            val user = KubeConfigUser.fromMap(map)

            // then
            Assertions.assertThat(user.token).isEqualTo("my-secret-token")
            Assertions.assertThat(user.clientCertificateData).isEqualTo("cert-data")
            Assertions.assertThat(user.clientKeyData).isEqualTo("key-data")
            Assertions.assertThat(user.username).isEqualTo("admin")
            Assertions.assertThat(user.password).isEqualTo("secret")
        }

        @Test
        fun `#fromMap returns empty user for empty map`() {
            // given
            // empty map

            // when
            val user = KubeConfigUser.fromMap(emptyMap<String, Any>())

            Assertions.assertThat(user.token).isNull()
            Assertions.assertThat(user.clientCertificateData).isNull()
            Assertions.assertThat(user.clientKeyData).isNull()
            Assertions.assertThat(user.username).isNull()
            Assertions.assertThat(user.password).isNull()
        }

        @Test
        fun `#fromMap is handling non-string values gracefully`() {
            // given
            val map = mapOf(
                "token" to 12345,  // non-string
                "client-certificate-data" to listOf("not", "string"),  // non-string
                "client-key-data" to true,  // non-string
                "username" to mapOf("not" to "string"),  // non-string
                "password" to 3.14  // non-string
            )

            val user = KubeConfigUser.fromMap(map)

            // All should be null since they're not strings
            Assertions.assertThat(user.token).isNull()
            Assertions.assertThat(user.clientCertificateData).isNull()
            Assertions.assertThat(user.clientKeyData).isNull()
            Assertions.assertThat(user.username).isNull()
            Assertions.assertThat(user.password).isNull()
        }
    }

    @Nested
    inner class KubeConfigNamedUserTest {

        @Test
        fun `#fromMap is parsing named user`() {
            // given
            val userObject = mapOf(
                "user" to mapOf(
                    "token" to "my-secret-token"
                )
            )

            // when
            val namedUser = KubeConfigNamedUser.fromMap("my-user", userObject)

            // then
            Assertions.assertThat(namedUser).isNotNull
            Assertions.assertThat(namedUser?.name).isEqualTo("my-user")
            Assertions.assertThat(namedUser?.user?.token).isEqualTo("my-secret-token")
        }

        @Test
        fun `#fromMap returns null when user key is missing`() {
            // given
            val userObject = mapOf(
                "name" to "my-user"
            )

            // when
            val namedUser = KubeConfigNamedUser.fromMap("my-user", userObject)

            // then
            Assertions.assertThat(namedUser).isNull()
        }

        @Test
        fun `#isTokenAuth returns true when current user has token`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.credentials } returns mapOf(KubeConfig.CRED_TOKEN_KEY to "Help me, Obi-Wan Kenobi")

            // when
            val isTokenAuth = KubeConfigNamedUser.isTokenAuth(kubeConfig)

            // then
            Assertions.assertThat(isTokenAuth).isTrue()
        }

        @Test
        fun `#isTokenAuth returns false when current user has no token`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.credentials } returns emptyMap<String, String>()

            // when
            val isTokenAuth = KubeConfigNamedUser.isTokenAuth(kubeConfig)

            // then
            Assertions.assertThat(isTokenAuth).isFalse()
        }

        @Test
        fun `#isTokenAuth returns false when current user is null`() {
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.credentials } returns null

            // when
            val isTokenAuth = KubeConfigNamedUser.isTokenAuth(kubeConfig)

            // then
            Assertions.assertThat(isTokenAuth).isFalse()
        }

        @Test
        fun `#findUserTokenForCluster finds token for cluster`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.contexts } returns arrayListOf<Any>(
                mapOf(
                    "name" to "skywalker-context",
                    "context" to mapOf(
                        "cluster" to "skywalker-cluster",
                        "user" to "skywalker"
                    )
                )
            )
            every { kubeConfig.users } returns arrayListOf<Any>(
                mapOf(
                    "name" to "skywalker",
                    "user" to mapOf("token" to "secret-token-123")
                )
            )

            // when
            val token = KubeConfigNamedUser.getUserTokenForCluster("skywalker-cluster", kubeConfig)

            // then
            Assertions.assertThat(token).isEqualTo("secret-token-123")
        }

        @Test
        fun `#findUserTokenForCluster returns null when context not found`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.contexts } returns arrayListOf<Any>(
                mapOf(
                    "name" to "skywalker-context",
                    "context" to mapOf(
                        "cluster" to "skywalker-cluster",
                        "user" to "skywalker"
                    )
                )
            )

            // when
            val token = KubeConfigNamedUser.getUserTokenForCluster("nonexistent", kubeConfig)

            Assertions.assertThat(token).isNull()
        }

        @Test
        fun `#findUserTokenForCluster returns null when user not found`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.contexts } returns arrayListOf<Any>(
                mapOf(
                    "name" to "skywalker-context",
                    "context" to mapOf(
                        "cluster" to "skywalker-cluster",
                        "user" to "skywalker"
                    )
                )
            )
            every { kubeConfig.users } returns arrayListOf<Any>(
                mapOf(
                    "name" to "different-user",
                    "user" to mapOf("token" to "secret-token-123")
                )
            )

            // when
            val token = KubeConfigNamedUser.getUserTokenForCluster("skywalker-cluster", kubeConfig)

            // then
            Assertions.assertThat(token).isNull()
        }

        @Test
        fun `#findUserTokenForCluster returns null when user has no token`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.contexts } returns arrayListOf<Any>(
                mapOf(
                    "name" to "skywalker-context",
                    "context" to mapOf(
                        "cluster" to "skywalker-cluster",
                        "user" to "skywalker"
                    )
                )
            )
            every { kubeConfig.users } returns arrayListOf<Any>(
                mapOf(
                    "name" to "skywalker",
                    "user" to mapOf("client-certificate-data" to "cert")
                )
            )

            // when
            val token = KubeConfigNamedUser.getUserTokenForCluster("skywalker-cluster", kubeConfig)

            // then
            Assertions.assertThat(token).isNull()
        }

        @Test
        fun `#fromMap returns null when userObject is not a Map`() {
            // given
            // invalid userObject (string instead of map)

            // when
            val namedUser = KubeConfigNamedUser.fromMap("my-user", "not-a-map")

            Assertions.assertThat(namedUser).isNull()
        }

        @Test
        fun `#fromMap returns null when userObject is null`() {
            // given
            // null userObject

            // when
            val namedUser = KubeConfigNamedUser.fromMap("my-user", null)

            // then
            Assertions.assertThat(namedUser).isNull()
        }

        @Test
        fun `#findUserTokenForCluster is handling users is null`() {
            // given
            val kubeConfig = mockk<KubeConfig>()
            every { kubeConfig.contexts } returns arrayListOf<Any>(
                mapOf(
                    "name" to "skywalker-context",
                    "context" to mapOf(
                        "cluster" to "skywalker-cluster",
                        "user" to "skywalker"
                    )
                )
            )
            every { kubeConfig.users } returns null

            val token = KubeConfigNamedUser.getUserTokenForCluster("skywalker-cluster", kubeConfig)

            // then
            Assertions.assertThat(token).isNull()
        }
    }
}