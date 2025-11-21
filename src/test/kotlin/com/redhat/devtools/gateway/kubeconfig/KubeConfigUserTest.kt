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

class KubeConfigUserTest {

    @Test
    fun `#fromMap is parsing user with token`() {
        // given
        val map = mapOf(
            "token" to "my-secret-token"
        )

        // when
        val user = KubeConfigUser.fromMap(map)

        // then
        assertThat(user.token).isEqualTo("my-secret-token")
        assertThat(user.clientCertificateData).isNull()
        assertThat(user.clientKeyData).isNull()
        assertThat(user.username).isNull()
        assertThat(user.password).isNull()
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
        assertThat(user.token).isEqualTo("my-secret-token")
        assertThat(user.clientCertificateData).isEqualTo("cert-data")
        assertThat(user.clientKeyData).isEqualTo("key-data")
        assertThat(user.username).isEqualTo("admin")
        assertThat(user.password).isEqualTo("secret")
    }

    @Test
    fun `#fromMap returns empty user for empty map`() {
        // given
        // empty map

        // when
        val user = KubeConfigUser.fromMap(emptyMap<String, Any>())

        assertThat(user.token).isNull()
        assertThat(user.clientCertificateData).isNull()
        assertThat(user.clientKeyData).isNull()
        assertThat(user.username).isNull()
        assertThat(user.password).isNull()
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
        assertThat(user.token).isNull()
        assertThat(user.clientCertificateData).isNull()
        assertThat(user.clientKeyData).isNull()
        assertThat(user.username).isNull()
        assertThat(user.password).isNull()
    }

    @Test
    fun `#toMap returns map with all fields`() {
        // given
        val user = KubeConfigUser(
            token = "DeathStar-token",
            clientCertificateData = "Vader-cert",
            clientKeyData = "Vader-key",
            username = "DarthVader",
            password = "DarkSide"
        )

        // when
        val map = user.toMap()

        // then
        assertThat(map)
            .hasSize(5)
            .containsEntry("token", "DeathStar-token")
            .containsEntry("client-certificate-data", "Vader-cert")
            .containsEntry("client-key-data", "Vader-key")
            .containsEntry("username", "DarthVader")
            .containsEntry("password", "DarkSide")
    }

    @Test
    fun `#toMap returns map with only token`() {
        // given
        val user = KubeConfigUser(
            token = "Rebel-Alliance-Token"
        )

        // when
        val map = user.toMap()

        // then
        assertThat(map)
            .hasSize(1)
            .containsEntry("token", "Rebel-Alliance-Token")
    }
}