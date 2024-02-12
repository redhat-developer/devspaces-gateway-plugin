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
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1PodList

class Pods(private val client: ApiClient) {
    @Throws(ApiException::class)
    fun list(namespace: String, labelSelector: String = ""): V1PodList {
        val customApi = CoreV1Api(client)
        return customApi.listNamespacedPod(
            namespace,
            "false",
            false,
            "",
            "",
            labelSelector,
            -1,
            "",
            "",
            -1,
            false
        )
    }
}