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

import io.kubernetes.client.openapi.models.V1Pod
import java.io.IOException

class DevSpacesGatewayConnection(private val devSpacesContext: DevSpacesContext) {
    @Throws(Exception::class)
    fun connect() {
        val name = Utils.getValue(devSpacesContext.devWorkspace, arrayOf("metadata", "name")) as String
        val namespace = Utils.getValue(devSpacesContext.devWorkspace, arrayOf("metadata", "namespace")) as String

        val podList = Pods(devSpacesContext.client).list(
            namespace,
            String.format("controller.devfile.io/devworkspace_name=%s", name)
        )

        if (podList.items.size != 1) throw IOException(String.format("Expected 1 pod, but found %d", podList.items.size))
        val pod = podList.items[0]

        val connectionURI = getConnectionURI(pod)
        if (connectionURI == "") throw IOException("Connection URI not found")

        val devSpacesPortForward = DevSpacesPortForward(devSpacesContext.client)
        devSpacesPortForward.start(pod)

        // TODO CONNECT
    }

    private fun getConnectionURI(pod: V1Pod): String {
        for (container in pod.spec?.containers!!) {
            try {
                val result = Exec(devSpacesContext.client).run(
                    pod,
                    arrayOf("grep", "-Eo", "-m1", "tcp://.*", "/idea-server/std.out"),
                    container.name
                )
                if (result != "") return result
            } catch (e: Exception) {
                continue
            }
        }

        return ""
    }
}
