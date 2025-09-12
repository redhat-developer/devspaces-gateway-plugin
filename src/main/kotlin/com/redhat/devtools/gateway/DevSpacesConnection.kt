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
package com.redhat.devtools.gateway

import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.redhat.devtools.gateway.openshift.DevWorkspaces
import com.redhat.devtools.gateway.openshift.Pods
import com.redhat.devtools.gateway.server.RemoteIDEServer
import io.kubernetes.client.openapi.ApiException
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
        if (devSpacesContext.isConnected)
            throw IOException(String.format("Already connected to %s", devSpacesContext.devWorkspace.metadata.name))

        devSpacesContext.isConnected = true
        try {
            return doConnect(onConnected, onDevWorkspaceStopped, onDisconnected)
        } catch (e: Exception) {
            devSpacesContext.isConnected = false
            throw e
        }
    }

    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    private fun doConnect(
        onConnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        onDisconnected: () -> Unit
    ): ThinClientHandle {
        startAndWaitDevWorkspace()

        val remoteIdeServer = RemoteIDEServer(devSpacesContext)
        val remoteIdeServerStatus = remoteIdeServer.getStatus()
        val joinLink = remoteIdeServerStatus.joinLink
            ?: throw IOException("Could not connect, remote IDE is not ready. No join link present.")
        val client = LinkedClientManager
            .getInstance()
            .startNewClient(
                Lifetime.Eternal,
                URI(joinLink),
                "",
                onConnected,
                false
            )

        val forwarder = Pods(devSpacesContext.client).forward(remoteIdeServer.pod, 5990, 5990)

        client.run {
            lifetime.onTermination { forwarder.close() }
            lifetime.onTermination {
                if (remoteIdeServer.waitServerTerminated())
                    DevWorkspaces(devSpacesContext.client)
                        .stop(
                            devSpacesContext.devWorkspace.metadata.namespace,
                            devSpacesContext.devWorkspace.metadata.name
                        )
                        .also { onDevWorkspaceStopped() }
            }
            lifetime.onTermination { devSpacesContext.isConnected = false }
            lifetime.onTermination(onDisconnected)
        }

        return client
    }

    @Throws(IOException::class, ApiException::class)
    private fun startAndWaitDevWorkspace() {
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
            "DevWorkspace '${devSpacesContext.devWorkspace.metadata.name}' is not running after ${DevWorkspaces.RUNNING_TIMEOUT} seconds"
        )
    }
}
