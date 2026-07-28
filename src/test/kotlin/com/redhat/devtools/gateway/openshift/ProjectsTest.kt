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
import io.kubernetes.client.openapi.apis.AuthorizationV1Api
import io.kubernetes.client.openapi.models.V1SelfSubjectAccessReview
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
        mockkConstructor(AuthorizationV1Api::class)
    }

    @AfterEach
    fun afterEach() {
        unmockkConstructor(AuthorizationV1Api::class)
    }

    @Test
    fun `#isAuthenticated returns true when API call succeeds`() {
        stubSelfSubjectAccessReview {
            mockk<V1SelfSubjectAccessReview>(relaxed = true)
        }

        assertThat(projects.isAuthenticated()).isTrue()
    }

    @Test
    fun `#isAuthenticated returns false when API returns 401`() {
        stubSelfSubjectAccessReviewFailure(ApiException(401, "Unauthorized"))

        assertThat(projects.isAuthenticated()).isFalse()
    }

    @Test
    fun `#isAuthenticated returns true when API returns 403`() {
        stubSelfSubjectAccessReviewFailure(ApiException(403, "Forbidden"))

        assertThat(projects.isAuthenticated()).isTrue()
    }

    @Test
    fun `#isAuthenticated rethrows ApiException for other error codes`() {
        stubSelfSubjectAccessReviewFailure(ApiException(500, "Internal Server Error"))

        assertThatThrownBy { projects.isAuthenticated() }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(500)
    }

    private fun stubSelfSubjectAccessReview(onExecute: () -> V1SelfSubjectAccessReview) {
        every {
            anyConstructed<AuthorizationV1Api>().createSelfSubjectAccessReview(any())
        } returns mockk {
            every { execute() } answers { onExecute() }
        }
    }

    private fun stubSelfSubjectAccessReviewFailure(exception: ApiException) {
        every {
            anyConstructed<AuthorizationV1Api>().createSelfSubjectAccessReview(any())
        } returns mockk {
            every { execute() } throws exception
        }
    }
}
