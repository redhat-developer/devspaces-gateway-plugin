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
package com.github.devspaces.gateway.openshift

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CustomObjectsApi

class DevWorkspaces(private val client: ApiClient) {
    @Throws(ApiException::class)
    fun list(namespace: String): Any {
        val customApi = CustomObjectsApi(client)
        return customApi.listNamespacedCustomObject(
            "workspace.devfile.io",
            "v1alpha2",
            namespace,
            "devworkspaces",
            "false",
            false,
            "",
            "",
            "",
            -1,
            "",
            "",
            -1,
            false
        )
    }
}