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

import com.redhat.devtools.gateway.auth.tls.CertificateSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KubeConfigClusterTest {
    @Test
    fun `#fromMap is parsing cluster with all fields`() {
        // given
        val map = mapOf(
            "server" to "https://api.example.com:6443",
            "certificate-authority-data" to "LS0tLS1CRUdJTi...", // notsecret
            "insecure-skip-tls-verify" to true
        )

        // when
        val cluster = KubeConfigCluster.fromMap(map)

        // then
        assertThat(cluster).isNotNull
        assertThat(cluster?.server).isEqualTo("https://api.example.com:6443")
        assertThat(cluster?.certificateAuthority?.value).isEqualTo("LS0tLS1CRUdJTi...") // notsecret
        assertThat(cluster?.certificateAuthority?.isFilePath).isFalse()
        assertThat(cluster?.insecureSkipTlsVerify).isTrue()
    }

    @Test
    fun `#fromMap parses cluster with certificate-authority path`() {
        val map = mapOf(
            "server" to "https://api.example.com:6443",
            "certificate-authority" to "/home/user/.minikube/ca.crt"
        )

        val cluster = KubeConfigCluster.fromMap(map)

        assertThat(cluster).isNotNull
        assertThat(cluster?.certificateAuthority?.value).isEqualTo("/home/user/.minikube/ca.crt")
        assertThat(cluster?.certificateAuthority?.isFilePath).isTrue()
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
        assertThat(cluster?.certificateAuthority).isNull()
        assertThat(cluster?.insecureSkipTlsVerify).isNull()
    }

    @Test
    fun `#fromMap returns null when server is missing`() {
        // given
        val map = mapOf(
            "certificate-authority-data" to "LS0tLS1CRUdJTi..." // notsecret - missing server
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
            certificateAuthority = CertificateSource.fromData("LS0tLS1CRUdJTi1MSUdIVF..."), // notsecret
            insecureSkipTlsVerify = true
        )

        // when
        val map = cluster.toMap()

        // then
        assertThat(map)
            .hasSize(3)
            .containsEntry("server", "https://tatooine.starwars.galaxy:6443")
            .containsEntry("certificate-authority-data", "LS0tLS1CRUdJTi1MSUdIVF...") // notsecret
            .containsEntry("insecure-skip-tls-verify", true)
    }

    @Test
    fun `#toMap writes certificate-authority path when isFilePath is true`() {
        val cluster = KubeConfigCluster(
            server = "https://tatooine.starwars.galaxy:6443",
            certificateAuthority = CertificateSource.fromPath("/home/user/.minikube/ca.crt")
        )

        val map = cluster.toMap()

        assertThat(map)
            .hasSize(2)
            .containsEntry("server", "https://tatooine.starwars.galaxy:6443")
            .containsEntry("certificate-authority", "/home/user/.minikube/ca.crt")
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
