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
package com.redhat.devtools.gateway.openshift

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProjectsTest {

    private lateinit var client: ApiClient
    private lateinit var projects: Projects

    @BeforeEach
    fun beforeEach() {
        client = mockk(relaxed = true)
        projects = Projects(client)
        mockkConstructor(CoreV1Api::class)
    }

    @AfterEach
    fun afterEach() {
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#isAuthenticated returns true when API call succeeds`() {
        // given
        every {
            anyConstructed<CoreV1Api>().listNamespace()
        } returns mockk {
            every { execute() } returns mockk()
        }

        // when
        val result = projects.isAuthenticated()

        // then
        assertThat(result).isTrue()
    }

    @Test
    fun `#isAuthenticated returns false when API returns 401`() {
        // given
        every {
            anyConstructed<CoreV1Api>().listNamespace()
        } returns mockk {
            every { execute() } throws ApiException(401, "Unauthorized")
        }

        // when
        val result = projects.isAuthenticated()

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `#isAuthenticated returns true when API returns 403`() {
        // given
        every {
            anyConstructed<CoreV1Api>().listNamespace()
        } returns mockk {
            every { execute() } throws ApiException(403, "Forbidden")
        }

        // when
        val result = projects.isAuthenticated()

        // then
        assertThat(result).isTrue()
    }

    @Test
    fun `#isAuthenticated rethrows ApiException for other error codes`() {
        // given
        every {
            anyConstructed<CoreV1Api>().listNamespace()
        } returns mockk {
            every { execute() } throws ApiException(500, "Internal Server Error")
        }

        // when/then
        org.assertj.core.api.Assertions.assertThatThrownBy {
            projects.isAuthenticated()
        }.isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(500)
    }
}
