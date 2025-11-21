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

class KubeConfigNamedClusterTest {

    @Test
    fun `#fromMap is parsing named cluster`() {
        // given
        val clusterObject = mapOf(
            "name" to "my-cluster",
            "cluster" to mapOf(
                "server" to "https://api.example.com:6443",
                "certificate-authority-data" to "LS0tLS1CRUdJTi..."
            )
        )

        // when
        val namedCluster = KubeConfigNamedCluster.fromMap(clusterObject)

        // then
        assertThat(namedCluster).isNotNull
        assertThat(namedCluster?.name).isEqualTo("my-cluster")
        assertThat(namedCluster?.cluster?.server).isEqualTo("https://api.example.com:6443")
    }

    @Test
    fun `#fromMap returns null when cluster details are invalid`() {
        // given
        val clusterObject = mapOf(
            "name" to "my-cluster",
            "cluster" to mapOf(
                "invalid" to "data"
            )
        )

        // when
        val namedCluster = KubeConfigNamedCluster.fromMap(clusterObject)

        // then
        assertThat(namedCluster).isNull()
    }

    @Test
    fun `#fromMap returns null when cluster key is missing`() {
        // given
        val clusterObject = mapOf(
            "name" to "my-cluster"
        )

        // when
        val namedCluster = KubeConfigNamedCluster.fromMap(clusterObject)

        // then
        assertThat(namedCluster).isNull()
    }

    @Test
    fun `#toMap returns map representation`() {
        // given
        val namedCluster = KubeConfigNamedCluster(
            name = "Death-Star-Cluster",
            cluster = KubeConfigCluster(server = "https://alderaan.starwars.galaxy")
        )

        // when
        val map = namedCluster.toMap()

        // then
        assertThat(map)
            .hasSize(2)
            .containsEntry("name", "Death-Star-Cluster")
        val clusterMap = map["cluster"] as? Map<String, Any>
        assertThat(clusterMap)
            .isNotNull
            .containsEntry("server", "https://alderaan.starwars.galaxy")
    }
}