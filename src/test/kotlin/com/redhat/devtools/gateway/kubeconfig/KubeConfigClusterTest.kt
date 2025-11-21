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

class KubeConfigClusterTest {
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
        assertThat(cluster).isNotNull
        assertThat(cluster?.server).isEqualTo("https://api.example.com:6443")
        assertThat(cluster?.certificateAuthorityData).isEqualTo("LS0tLS1CRUdJTi...")
        assertThat(cluster?.insecureSkipTlsVerify).isTrue()
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
        assertThat(cluster).isNotNull
        assertThat(cluster?.server).isEqualTo("https://api.example.com:6443")
        assertThat(cluster?.certificateAuthorityData).isNull()
        assertThat(cluster?.insecureSkipTlsVerify).isNull()
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
        assertThat(cluster).isNull()
    }

    @Test
    fun `#fromMap returns null for empty map`() {
        // given
        // empty map

        // when
        val cluster = KubeConfigCluster.fromMap(emptyMap<String, Any>())

        // then
        assertThat(cluster).isNull()
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
        assertThat(cluster).isNull()
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
        assertThat(cluster).isNotNull
        assertThat(cluster?.insecureSkipTlsVerify).isNull()
    }

    @Test
    fun `#toMap returns map with all fields`() {
        // given
        val cluster = KubeConfigCluster(
            server = "https://tatooine.starwars.galaxy:6443",
            certificateAuthorityData = "LS0tLS1CRUdJTi1MSUdIVF..." /* A long time ago in a galaxy far, far away... */,
            insecureSkipTlsVerify = true
        )

        // when
        val map = cluster.toMap()

        // then
        assertThat(map)
            .hasSize(3)
            .containsEntry("server", "https://tatooine.starwars.galaxy:6443")
            .containsEntry("certificate-authority-data", "LS0tLS1CRUdJTi1MSUdIVF...")
            .containsEntry("insecure-skip-tls-verify", true)
    }

    @Test
    fun `#toMap returns map with only server`() {
        // given
        val cluster = KubeConfigCluster(
            server = "https://endor.starwars.galaxy:6443"
        )

        // when
        val map = cluster.toMap()

        // then
        assertThat(map)
            .hasSize(1)
            .containsEntry("server", "https://endor.starwars.galaxy:6443")
    }
}