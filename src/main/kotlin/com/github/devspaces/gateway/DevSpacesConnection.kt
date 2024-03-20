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
import com.github.devspaces.gateway.server.RemoteServer
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import java.io.Closeable
import java.io.IOException
import java.net.URI

class DevSpacesConnection(private val devSpacesContext: DevSpacesContext) {
    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    fun connect(
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
    ): ThinClientHandle {
        if (!devSpacesContext.devWorkspace.spec.started) {
            DevWorkspaces(devSpacesContext.client)
                .start(
                    devSpacesContext.devWorkspace.metadata.namespace,
                    devSpacesContext.devWorkspace.metadata.name
                )
        }

        if (!DevWorkspaces(devSpacesContext.client)
                .waitPhase(
                    devSpacesContext.devWorkspace.metadata.namespace,
                    devSpacesContext.devWorkspace.metadata.name,
                    DevWorkspaces.RUNNING,
                    DevWorkspaces.RUNNING_TIMEOUT
                )
        ) throw IOException(
            String.format(
                "DevWorkspace '%s' is not running after %d seconds",
                devSpacesContext.devWorkspace.metadata.name,
                DevWorkspaces.RUNNING_TIMEOUT
            )
        )


        val remoteServer = RemoteServer(devSpacesContext).also { it.waitProjectsReady() }
        val projectStatus = remoteServer.getProjectStatus()

        if (projectStatus.joinLink.isEmpty()) throw IOException(
            String.format(
                "Connection link to the remote server not found in the DevWorkspace: %s",
                devSpacesContext.devWorkspace.metadata.name
            )
        )

        val client = LinkedClientManager
            .getInstance()
            .startNewClient(
                Lifetime.Eternal,
                URI(projectStatus.joinLink),
                "",
                onConnected
            )

        val forwarder = Pods(devSpacesContext.client).forward(remoteServer.pod, 5990, 5990)
        client.run {
            lifetime.onTermination(forwarder)
            lifetime.onTermination {
                if (remoteServer.waitProjectsTerminated()) {
                    DevWorkspaces(devSpacesContext.client)
                        .stop(
                            devSpacesContext.devWorkspace.metadata.namespace,
                            devSpacesContext.devWorkspace.metadata.name
                        )
                    onDevWorkspaceStopped()
                }
            }
            lifetime.onTermination(onDisconnected)
        }

        return client
    }
}
