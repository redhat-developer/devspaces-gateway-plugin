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

import io.kubernetes.client.openapi.apis.CoreV1Api
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

    @Throws(ApiException::class)
    fun isAuthenticated(): Boolean {
        list()
        // throws if not authenticated
        return true
    }

    /**
     * Check if the token is valid and usable for the namespace.
     * Works for user OAuth tokens and pipeline SA tokens.
     */
    @Throws(ApiException::class)
    fun isAuthenticatedAlternative(): Boolean {
        val api = AuthorizationV1Api(client)

        val review = V1SelfSubjectAccessReview().apply {
            spec = V1SelfSubjectAccessReviewSpec().apply {
                resourceAttributes = V1ResourceAttributes().apply {
                    verb = "get"
                    resource = "namespaces"
                }
            }
        }

        val response = api
            .createSelfSubjectAccessReview(review)
            .execute()

        return response.status?.allowed == true
    }
}