/*
 * Copyright (c) 2024 Red Hat, Inc.
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
import io.kubernetes.client.openapi.apis.CustomObjectsApi

import io.kubernetes.client.openapi.apis.AuthorizationV1Api
import io.kubernetes.client.openapi.models.V1SelfSubjectAccessReview
import io.kubernetes.client.openapi.models.V1SelfSubjectAccessReviewSpec
import io.kubernetes.client.openapi.models.V1ResourceAttributes

class Projects(private val client: ApiClient) {
    @Throws(ApiException::class)
    fun list(): List<*> {
        val customApi = CustomObjectsApi(client)
        val response = customApi.listClusterCustomObject(
            "project.openshift.io",
            "v1",
            "projects"
        ).execute() as Map<*, *>

        return response["items"] as List<*>
    }

    /**
     * Checks if the client is authenticated (credentials are valid).
     * Returns `true` if authenticated, `false` if credentials are invalid.
     *
     * Note: This distinguishes authentication (401) from authorization (403).
     * A user can be authenticated but lack permissions - that still returns true.
     *
     * @return `true` if user is authenticated, `false` otherwise
     */
    @Throws(ApiException::class)
    fun isAuthenticated(): Boolean {
        return try {
            AuthorizationV1Api(client).createSelfSubjectAccessReview(
                V1SelfSubjectAccessReview()
                    .spec(
                        V1SelfSubjectAccessReviewSpec()
                            .resourceAttributes(
                                V1ResourceAttributes()
                                    .group("project.openshift.io")
                                    .resource("projects")
                                    .verb("list")
                            )
                    )
            ).execute()
            true  // 201 Created - authenticated; access result is not relevant here
        } catch (e: ApiException) {
            when (e.code) {
                401 -> false  // Unauthorized - not authenticated
                403 -> true   // Forbidden - authenticated but not authorized
                else -> throw e
            }
        }
    }
}