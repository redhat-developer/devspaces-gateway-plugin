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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KubeConfigContextTest {

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
        assertThat(context).isNotNull
        assertThat(context?.cluster).isEqualTo("my-cluster")
        assertThat(context?.user).isEqualTo("my-user")
        assertThat(context?.namespace).isEqualTo("my-namespace")
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
        assertThat(context).isNotNull
        assertThat(context?.cluster).isEqualTo("my-cluster")
        assertThat(context?.user).isEqualTo("my-user")
        assertThat(context?.namespace).isNull()
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
        assertThat(context).isNull()
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
        assertThat(context).isNull()
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
        assertThat(context).isNull()
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
        assertThat(context).isNull()
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
        assertThat(context).isNotNull
        assertThat(context?.cluster).isEqualTo("my-cluster")
        assertThat(context?.user).isEqualTo("my-user")
        assertThat(context?.namespace).isNull()
    }

    @Test
    fun `#toMap returns map with all fields`() {
        // given
        val context = KubeConfigContext(
            user = "Yoda",
            cluster = "Dagobah-cluster",
            namespace = "Jedi-Temple"
        )

        // when
        val map = context.toMap()

        // then
        assertThat(map)
            .hasSize(3)
            .containsEntry("cluster", "Dagobah-cluster")
            .containsEntry("user", "Yoda")
            .containsEntry("namespace", "Jedi-Temple")
    }

    @Test
    fun `#toMap returns map without namespace`() {
        // given
        val context = KubeConfigContext(
            cluster = "Tatooine-cluster",
            user = "Luke-Skywalker"
        )

        // when
        val map = context.toMap()

        // then
        assertThat(map)
            .hasSize(2)
            .containsEntry("cluster", "Tatooine-cluster")
            .containsEntry("user", "Luke-Skywalker")
        assertThat(map).doesNotContainKey("namespace")
    }
}