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
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KubeConfigNamedUserTest {

    @Test
    fun `#fromMap is parsing named user`() {
        // given
        val userObject = mapOf(
            "name" to "DarthVader",
            "user" to mapOf(
                "token" to "dark-force"
            )
        )

        // when
        val namedUser = KubeConfigNamedUser.fromMap(userObject)

        // then
        assertThat(namedUser).isNotNull
        assertThat(namedUser?.name).isEqualTo("DarthVader")
        assertThat(namedUser?.user?.token).isEqualTo("dark-force")
    }

    @Test
    fun `#fromMap returns null when user key is missing`() {
        // given
        val userObject = mapOf(
            "name" to "my-user"
        )

        // when
        val namedUser = KubeConfigNamedUser.fromMap(userObject)

        // then
        assertThat(namedUser).isNull()
    }

    @Test
    fun `#isTokenAuth returns true when current user has token`() {
        // given
        val kubeConfig = KubeConfigTestHelpers.createMockKubeConfig(
            credentials = mapOf(KubeConfig.CRED_TOKEN_KEY to "Help me, Obi-Wan Kenobi")
        )

        // when
        val isTokenAuth = KubeConfigNamedUser.isTokenAuth(kubeConfig)

        assertThat(isTokenAuth).isTrue()
    }

    @Test
    fun `#isTokenAuth returns false when current user has no token`() {
        // given
        val kubeConfig = KubeConfigTestHelpers.createMockKubeConfig(
            credentials = emptyMap()
        )

        // when
        val isTokenAuth = KubeConfigNamedUser.isTokenAuth(kubeConfig)

        // then
        assertThat(isTokenAuth).isFalse()
    }

    @Test
    fun `#isTokenAuth returns false when current user is null`() {
        val kubeConfig = KubeConfigTestHelpers.createMockKubeConfig()
        every { kubeConfig.credentials } returns null

        // when
        val isTokenAuth = KubeConfigNamedUser.isTokenAuth(kubeConfig)

        // then
        assertThat(isTokenAuth).isFalse()
    }

    @Test
    fun `#findUserTokenForCluster finds token for cluster`() {
        // given
        val kubeConfig = KubeConfigTestHelpers.createMockKubeConfig(
            contexts = listOf(
                KubeConfigTestHelpers.createContextMap("skywalker-context", "skywalker-cluster", "skywalker")
            ),
            users = listOf(
                KubeConfigTestHelpers.createUserMap("skywalker", "secret-token-123")
            )
        )

        // when
        val token = KubeConfigNamedUser.getUserTokenForCluster("skywalker-cluster", kubeConfig)

        // then
        assertThat(token).isEqualTo("secret-token-123")
    }

    @Test
    fun `#findUserTokenForCluster returns null when context not found`() {
        // given
        val kubeConfig = KubeConfigTestHelpers.createMockKubeConfig(
            contexts = listOf(
                KubeConfigTestHelpers.createContextMap("skywalker-context", "skywalker-cluster", "skywalker")
            )
        )

        // when
        val token = KubeConfigNamedUser.getUserTokenForCluster("nonexistent", kubeConfig)

        assertThat(token).isNull()
    }

    @Test
    fun `#findUserTokenForCluster returns null when user not found`() {
        // given
        val kubeConfig = KubeConfigTestHelpers.createMockKubeConfig(
            contexts = listOf(
                KubeConfigTestHelpers.createContextMap("skywalker-context", "skywalker-cluster", "skywalker")
            ),
            users = listOf(
                KubeConfigTestHelpers.createUserMap("different-user", "secret-token-123")
            )
        )

        // when
        val token = KubeConfigNamedUser.getUserTokenForCluster("skywalker-cluster", kubeConfig)

        // then
        assertThat(token).isNull()
    }

    @Test
    fun `#findUserTokenForCluster returns null when user has no token`() {
        // given
        val kubeConfig = KubeConfigTestHelpers.createMockKubeConfig(
            contexts = listOf(
                KubeConfigTestHelpers.createContextMap("skywalker-context", "skywalker-cluster", "skywalker")
            ),
            users = listOf(
                mapOf(
                    "name" to "skywalker",
                    "user" to mapOf("client-certificate-data" to "cert")
                )
            )
        )

        // when
        val token = KubeConfigNamedUser.getUserTokenForCluster("skywalker-cluster", kubeConfig)

        // then
        assertThat(token).isNull()
    }

    @Test
    fun `#findUserTokenForCluster is handling users is null`() {
        // given
        val kubeConfig = KubeConfigTestHelpers.createMockKubeConfig(
            contexts = listOf(
                KubeConfigTestHelpers.createContextMap("skywalker-context", "skywalker-cluster", "skywalker")
            ),
            users = null
        )
        every { kubeConfig.users } returns null

        val token = KubeConfigNamedUser.getUserTokenForCluster("skywalker-cluster", kubeConfig)

        // then
        assertThat(token).isNull()
    }

    @Test
    fun `#toMap returns map representation`() {
        // given
        val namedUser = KubeConfigNamedUser(
            name = "Han-Solo",
            user = KubeConfigUser(token = "Millennium-Falcon-Key")
        )

        // when
        val map = namedUser.toMap()

        // then
        assertThat(map)
            .hasSize(2)
            .containsEntry("name", "Han-Solo")
        val userMap = map["user"] as? Map<String, Any>
        assertThat(userMap)
            .isNotNull
            .containsEntry("token", "Millennium-Falcon-Key")
    }
}