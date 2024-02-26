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
package com.github.devspaces.gateway

import com.github.devspaces.gateway.openshift.DevWorkspaces
import com.github.devspaces.gateway.openshift.Pods
import com.github.devspaces.gateway.openshift.Utils
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1Pod
import java.io.IOException
import java.net.URI

class DevSpacesConnection(private val devSpacesContext: DevSpacesContext) {
    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    fun connect(): ThinClientHandle {
        val isStarted = Utils.getValue(devSpacesContext.devWorkspace, arrayOf("spec", "started")) as Boolean
        val dwName = Utils.getValue(devSpacesContext.devWorkspace, arrayOf("metadata", "name")) as String
        val dwNamespace = Utils.getValue(devSpacesContext.devWorkspace, arrayOf("metadata", "namespace")) as String

        if (!isStarted) {
            DevWorkspaces(devSpacesContext.client).start(dwNamespace, dwName)
        }

        DevWorkspaces(devSpacesContext.client).waitRunning(dwNamespace, dwName)

        val dwPods = Pods(devSpacesContext.client)
            .list(
                dwNamespace,
                String.format("controller.devfile.io/devworkspace_name=%s", dwName)
            )

        if (dwPods.items.size != 1) throw IOException(
            String.format(
                "Expected 1 pod, but found %d",
                dwPods.items.size
            )
        )
        val dwPod = dwPods.items[0]

        val tcpLink = getConnectionLink(dwPod)
        if (tcpLink == "") throw IOException(
            String.format(
                "Connection link to the remote server not found in the Pod: %s",
                dwPod.metadata?.name
            )
        )

        val client = LinkedClientManager.getInstance().startNewClient(Lifetime.Eternal, URI(tcpLink), "")

        val forwarder = Pods(devSpacesContext.client).forward(dwPod, 5990, 5990)
        client.run { lifetime.onTermination(forwarder) }

        return client
    }

    @Throws(IOException::class, ApiException::class)
    private fun getConnectionLink(pod: V1Pod): String {
        val container =
            pod.spec?.containers!!.find { container -> container.ports!!.any { port -> port.name == "idea-server" } }
        if (container == null) throw IOException(
                String.format(
                    "Remote server container not found in the Pod: %s",
                    pod.metadata?.name
                )
        )

        // 1 minute to grab connection link
        for (i in 1..12) {
            val result = Pods(devSpacesContext.client).exec(
                pod,
                arrayOf("/bin/sh", "-c", "/idea-server/bin/remote-dev-server.sh status \$PROJECT_SOURCE | grep -Eo -m1 'tcp://[^\"]+'"),
                container.name
            ).trim()
            if (result.startsWith("tcp://")) return result

            // wait a bit, maybe remote server hasn't been started yet
            Thread.sleep(5 * 1000)
        }

        throw IOException(
            String.format(
                "Connection link to the remote server not found in the Pod: %s",
                pod.metadata?.name
            )
        )
    }
}
