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
}