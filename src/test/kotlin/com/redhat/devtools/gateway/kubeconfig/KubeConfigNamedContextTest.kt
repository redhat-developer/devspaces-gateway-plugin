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
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KubeConfigNamedContextTest {

    @Test
    fun `KubeConfigNamedContext#getByName finds context for cluster`() {
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
        val namedContext = KubeConfigNamedContext.getByName("skywalker-cluster", kubeConfig)

        // then
        assertThat(namedContext).isNotNull
        assertThat(namedContext?.name).isEqualTo("skywalker-context")
        assertThat(namedContext?.context?.cluster).isEqualTo("skywalker-cluster")
        assertThat(namedContext?.context?.user).isEqualTo("skywalker")
    }

    @Test
    fun `KubeConfigNamedContext#getByName returns null when cluster not found`() {
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
        val namedContext = KubeConfigNamedContext.getByName("nonexistent", kubeConfig)
        // then
        assertThat(namedContext).isNull()
    }

    @Test
    fun `KubeConfigNamedContext#getByName returns null when contexts is null`() {
        // given
        val kubeConfig = mockk<KubeConfig>()
        every { kubeConfig.contexts } returns null
        // when
        val namedContext = KubeConfigNamedContext.getByName("skywalker", kubeConfig)
        // then
        assertThat(namedContext).isNull()
    }

    @Test
    fun `KubeConfigNamedContext#getByName is handling contexts with missing context details`() {
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
        // when
        val namedContext = KubeConfigNamedContext.getByName("darth-vader-cluster", kubeConfig)
        // then
        assertThat(namedContext).isNotNull
        assertThat(namedContext?.name).isEqualTo("darth-vader-context")
    }

    @Test
    fun `KubeConfigNamedContext#getByName is handling non-map context objects`() {
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
        // when
        val namedContext = KubeConfigNamedContext.getByName("skywalker-cluster", kubeConfig)
        // then
        assertThat(namedContext).isNotNull
        assertThat(namedContext?.name).isEqualTo("skywalker-context")
    }

    @Test
    fun `KubeConfigNamedContext#getByName is handling contexts with missing names`() {
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
        // when
        val namedContext = KubeConfigNamedContext.getByName("darth-vader-cluster", kubeConfig)
        // then
        assertThat(namedContext).isNotNull
        assertThat(namedContext?.name).isEqualTo("darth-vader-context")
    }

    @Test
    fun `#toMap returns map representation`() {
        // given
        val namedContext = KubeConfigNamedContext(
            name = "Tatooine-Context",
            context = KubeConfigContext(cluster = "Tatooine-cluster", user = "Luke-Skywalker")
        )

        // when
        val map = namedContext.toMap()

        // then
        assertThat(map)
            .hasSize(2)
            .containsEntry("name", "Tatooine-Context")
        val contextMap = map["context"] as? Map<String, Any>
        assertThat(contextMap)
            .isNotNull
            .containsEntry("cluster", "Tatooine-cluster")
            .containsEntry("user", "Luke-Skywalker")
    }

    @Test
    fun `#name has user and cluster if given context has both not empty`() {
        // given
        val user = "luke-skywalker"
        val cluster = "tatooine-cluster"

        // when
        val context = KubeConfigNamedContext(
            KubeConfigContext(cluster = cluster, user = user)
        )

        // then
        assertThat(context.name).isEqualTo("luke-skywalker/tatooine-cluster")
    }

    @Test
    fun `#name has no special characters if given context has user and cluster with special characters`() {
        // given
        val user = "leia@alderaan.gov"
        val cluster = "death_star.cluster-complex"

        // when
        val context = KubeConfigNamedContext(
            KubeConfigContext(user, cluster)
        )

        // then
        assertThat(context.name).isEqualTo("leia-alderaan.gov/death-star.cluster-complex")
    }

    @Test
    fun `#name has only cluster if given context has empty user`() {
        // given
        val user = ""
        val cluster = "tatooine-cluster"

        // when
        val context = KubeConfigNamedContext(
            KubeConfigContext(user, cluster)
        )

        // then
        assertThat(context.name).isEqualTo("tatooine-cluster")
    }

    @Test
    fun `#name has just user if given context has empty cluster`() {
        // given
        val user = "luke-skywalker"
        val cluster = ""

        // when
        val context = KubeConfigNamedContext(
            KubeConfigContext(user, cluster)
        )

        // then
        assertThat(context.name).isEqualTo("luke-skywalker")
    }
}