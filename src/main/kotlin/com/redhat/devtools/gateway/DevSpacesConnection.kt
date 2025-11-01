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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.URI

class DevSpacesConnection(private val devSpacesContext: DevSpacesContext) {
    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    fun connect(
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
    ): ThinClientHandle {
        try {
            return doConnect(onConnected, onDevWorkspaceStopped, onDisconnected)
        } catch (e: Exception) {
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
        val workspace = devSpacesContext.devWorkspace
        devSpacesContext.addWorkspace(workspace)

        var remoteIdeServer: RemoteIDEServer? = null
        var forwarder: Closeable? = null

        return try {
            startAndWaitDevWorkspace()
            remoteIdeServer = RemoteIDEServer(devSpacesContext)
            val remoteIdeServerStatus = remoteIdeServer.getStatus()
            val joinLink = remoteIdeServerStatus.joinLink
                ?: throw IOException("Could not connect, remote IDE is not ready. No join link present.")

            val pods = Pods(devSpacesContext.client)
            val localPort = findFreePort()
            forwarder = pods.forward(remoteIdeServer.pod, localPort, 5990)
            pods.waitForForwardReady(localPort)

            val effectiveJoinLink = joinLink.replace(":5990", ":$localPort")

            val client = LinkedClientManager
                .getInstance()
                .startNewClient(
                    Lifetime.Eternal,
                    URI(effectiveJoinLink),
                    "",
                    onConnected, // Triggers enableButtons() via view
                    false
                )

            client.clientClosed.advise(client.lifetime) {
                onClientClosed(onDisconnected , onDevWorkspaceStopped, remoteIdeServer, forwarder)
            }

            client
        } catch (e: Exception) {
            onClientClosed(onDisconnected, onDevWorkspaceStopped, remoteIdeServer, forwarder)
            throw e
        }
    }

    private fun onClientClosed(
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        remoteIdeServer: RemoteIDEServer?,
        forwarder: Closeable?
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val currentWorkspace = devSpacesContext.devWorkspace
            try {
                onDisconnected.invoke()
                if (true == remoteIdeServer?.waitServerTerminated()) {
                    DevWorkspaces(devSpacesContext.client)
                        .stop(
                            devSpacesContext.devWorkspace.namespace,
                            devSpacesContext.devWorkspace.name
                        )
                        .also { onDevWorkspaceStopped() }
                }
                forwarder?.close()
            } finally {
                devSpacesContext.removeWorkspace(currentWorkspace)
                onDisconnected()
            }
        }
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }

    @Throws(IOException::class, ApiException::class)
    private fun startAndWaitDevWorkspace() {
        if (!devSpacesContext.devWorkspace.started) {
            DevWorkspaces(devSpacesContext.client)
                .start(
                    devSpacesContext.devWorkspace.namespace,
                    devSpacesContext.devWorkspace.name
                )
        }

        if (!DevWorkspaces(devSpacesContext.client)
                .waitPhase(
                    devSpacesContext.devWorkspace.namespace,
                    devSpacesContext.devWorkspace.name,
                    DevWorkspaces.RUNNING,
                    DevWorkspaces.RUNNING_TIMEOUT
                )
        ) throw IOException(
            "DevWorkspace '${devSpacesContext.devWorkspace.name}' is not running after ${DevWorkspaces.RUNNING_TIMEOUT} seconds"
        )
    }
}
