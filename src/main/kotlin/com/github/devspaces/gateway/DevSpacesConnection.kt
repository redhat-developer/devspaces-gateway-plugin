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

import com.github.devspaces.gateway.openshift.Pods
import com.github.devspaces.gateway.openshift.Utils
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import io.kubernetes.client.openapi.models.V1Pod
import java.io.Closeable
import java.io.IOException
import java.net.URI

class DevSpacesConnection(private val devSpacesContext: DevSpacesContext) {
    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    fun connect(onStarted: () -> Unit, onTerminated: () -> Unit): ThinClientHandle {
        val dwName = Utils.getValue(devSpacesContext.devWorkspace, arrayOf("metadata", "name")) as String
        val dwNamespace = Utils.getValue(devSpacesContext.devWorkspace, arrayOf("metadata", "namespace")) as String

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
                "Connection link to the remote server not found in Pod: %s",
                dwPod.metadata?.name
            )
        )

        val client = LinkedClientManager.getInstance().startNewClient(Lifetime.Eternal, URI(tcpLink), "", onStarted)

        val forwarder = Pods(devSpacesContext.client).forward(dwPod, 5990, 5990)
        client.run {
            lifetime.onTermination(
                Closeable {
                    forwarder.close()
                    onTerminated()
                }
            )
        }
        return client;
    }

    private fun getConnectionLink(pod: V1Pod): String {
        for (container in pod.spec?.containers!!) {
            try {
                val result = Pods(devSpacesContext.client).exec(
                    pod,
                    arrayOf("grep", "-Eo", "-m1", "tcp://.*", "/idea-server/std.out"),
                    container.name
                ).trim()
                if (result.startsWith("tcp://")) return result
            } catch (e: Exception) {
                continue
            }
        }
        return ""
    }
}
