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

import com.intellij.openapi.diagnostic.thisLogger
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.redhat.devtools.gateway.openshift.DevWorkspaces
import com.redhat.devtools.gateway.openshift.Pods
import com.redhat.devtools.gateway.server.RemoteIDEServer
import io.kubernetes.client.openapi.ApiException
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

        synchronized(devSpacesContext.activeWorkspaces) {
            if (devSpacesContext.activeWorkspaces.contains(workspace)) {
                throw IllegalStateException("Workspace '${workspace.name}' is already connected.")
            }
            devSpacesContext.activeWorkspaces.add(workspace)
        }

        var client: ThinClientHandle? = null
        var forwarder: AutoCloseable? = null

        try {
            startAndWaitDevWorkspace()

            val remoteIdeServer = RemoteIDEServer(devSpacesContext)
            val remoteIdeServerStatus = remoteIdeServer.getStatus()
            val joinLink = remoteIdeServerStatus.joinLink
                ?: throw IOException("Could not connect, remote IDE is not ready. No join link present.")

            val pods = Pods(devSpacesContext.client)
            val localPort = findFreePort()
            forwarder = pods.forward(remoteIdeServer.pod, localPort, 5990)
            pods.waitForForwardReady(localPort)

            val effectiveJoinLink = joinLink.replace(":5990", ":$localPort")

            client = LinkedClientManager
                .getInstance()
                .startNewClient(
                    Lifetime.Eternal,
                    URI(effectiveJoinLink),
                    "",
                    onConnected, // Triggers enableButtons() via view
                    false
                )

            client.run {
                lifetime.onTermination {
                    try {
                        forwarder.close()
                    } catch (_: Exception) {
                        // ignore cleanup errors
                    }
                }

                lifetime.onTermination {
                    try {
                        if (remoteIdeServer.waitServerTerminated()) {
                            DevWorkspaces(devSpacesContext.client)
                                .stop(
                                    devSpacesContext.devWorkspace.namespace,
                                    devSpacesContext.devWorkspace.name
                                )
                            onDevWorkspaceStopped() // UI refresh through callback
                        }
                    } finally {
                        synchronized(devSpacesContext.activeWorkspaces) {
                            devSpacesContext.activeWorkspaces.remove(workspace)
                        }
                        onDisconnected()
                    }
                }

                lifetime.onTermination {
                    onDisconnected() //  UI refresh through callback
                }
            }

            return client
        } catch (e: Exception) {
            try {
                disconnectAndCleanup(client, forwarder, onDevWorkspaceStopped, onDisconnected) // Cancel if started
            } catch (_: Exception) {}

            try {
                forwarder?.close()
            } catch (_: Exception) {}

            synchronized(devSpacesContext.activeWorkspaces) {
                devSpacesContext.activeWorkspaces.remove(workspace)
            }

            // Make sure UI refresh still happens on failure
            onDisconnected()

            throw e
        }
    }

    private fun disconnectAndCleanup(
        client: ThinClientHandle?,
        forwarder: AutoCloseable?,
        onDevWorkspaceStopped: () -> Unit,
        onDisconnected: () -> Unit
    ) {
        if (client == null) {
            onDisconnected()
            return
        }

        try {
            // Close the port forwarder first
            try {
                forwarder?.close()
            } catch (e: Exception) {
                thisLogger().debug("Failed to close port forwarder: ${e.message}")
            }

            // Stop workspace cleanly
            val devWorkspaces = DevWorkspaces(devSpacesContext.client)
            val workspace = devSpacesContext.devWorkspace

            try {
                devWorkspaces.stop(workspace.namespace, workspace.name)
                onDevWorkspaceStopped()
            } catch (e: Exception) {
                thisLogger().debug("Workspace stop failed: ${e.message}")
            }

            // Remove from active list and update state
            synchronized(devSpacesContext.activeWorkspaces) {
                devSpacesContext.activeWorkspaces.remove(workspace)
            }
            onDisconnected()

        } catch (e: Exception) {
            thisLogger().debug("Error while terminating client: ${e.message}")
            synchronized(devSpacesContext.activeWorkspaces) {
                devSpacesContext.activeWorkspaces.remove(devSpacesContext.devWorkspace)
            }
            onDisconnected()
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
