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
import com.github.devspaces.gateway.server.RemoteServer
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import org.bouncycastle.util.Arrays
import java.io.Closeable
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

        val remoteServer = RemoteServer(devSpacesContext)
        remoteServer.waitProjects()
        val projectStatus = remoteServer.getProjectStatus()

        if (projectStatus.joinLink.isEmpty()) throw IOException(
            String.format(
                "Connection link to the remote server not found in the DevWorkspace: %s",
                dwName
            )
        )

        val client = LinkedClientManager.getInstance().startNewClient(Lifetime.Eternal, URI(projectStatus.joinLink), "")
        val forwarder = Pods(devSpacesContext.client).forward(remoteServer.pod, 5990, 5990)
        client.run {
            lifetime.onTermination(forwarder)
            lifetime.onTermination(
                Closeable {
                    val projectStatus = remoteServer.getProjectStatus()
                    if (Arrays.isNullOrEmpty(projectStatus.projects)) {
                        DevWorkspaces(devSpacesContext.client).stop(dwNamespace, dwName)
                    }
                }
            )
        }

        return client
    }
}
