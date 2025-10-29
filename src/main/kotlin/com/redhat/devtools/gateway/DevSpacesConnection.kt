/*
 * Copyright (c) 2024-2025 Red Hat, Inc.
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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.redhat.devtools.gateway.openshift.DevWorkspaces
import com.redhat.devtools.gateway.openshift.Pods
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.server.RemoteIDEServerStatus
import io.kubernetes.client.openapi.ApiException
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CancellationException

class DevSpacesConnection(private val devSpacesContext: DevSpacesContext) {
    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    fun connect(
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        onProgress: ((message: String) -> Unit)? = null,
        checkCancelled: (() -> Unit)? = null
    ): ThinClientHandle {
        try {
            return doConnect(onConnected, onDevWorkspaceStopped, onDisconnected, onProgress, checkCancelled)
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
        onDisconnected: () -> Unit,
        onProgress: ((message: String) -> Unit)? = null,
        checkCancelled: (() -> Unit)? = null
    ): ThinClientHandle {
        checkCancelled?.invoke()

        onProgress?.invoke("Waiting for the Dev Workspace to get ready...")
        startAndWaitDevWorkspace(checkCancelled)

        checkCancelled?.invoke()

        onProgress?.invoke("Waiting for the Remote IDE server to get ready...")
        val (remoteIdeServer, remoteIdeServerStatus) = runCatching {
            val server = RemoteIDEServer(devSpacesContext).apply { waitServerReady(checkCancelled) }
            server to server.getStatus()
        }.getOrElse { null to RemoteIDEServerStatus.empty() }
        checkCancelled?.invoke()

        requireNotNull(remoteIdeServer) { "Could not connect, remote IDE is not ready." }
        require(remoteIdeServerStatus.joinLink != null && remoteIdeServerStatus.isReady) { "Remote IDE not ready or missing join link." }

        onProgress?.invoke("Waiting for the Remote IDE server to get ready...")
        checkCancelled?.invoke()

        var forwarder: Closeable? = null
        var client: ThinClientHandle? = null

        val cleanup: () -> Unit = {
            try { client?.close() } catch (_: Exception) {}
            closeForwarder(forwarder)
            stopDevWorkspace(remoteIdeServer, devSpacesContext, onDevWorkspaceStopped)
            invokeOnDisconnected(onDisconnected)
            devSpacesContext.isConnected = false
        }

        onProgress?.invoke("Client: Starting up the client...")
        try {
            val pods = Pods(devSpacesContext.client)
            // ✅ Dynamically find a free local port
            val localPort = findFreePort()
            val forwarder = pods.forward(remoteIdeServer.pod, localPort, 5990)
            pods.waitForForwardReady(localPort)
            val effectiveJoinLink = remoteIdeServerStatus.joinLink.replace(":5990", ":$localPort")

            val lifetimeDef = Lifetime.Eternal.createNested()
            lifetimeDef.lifetime.onTermination { cleanup() }

            checkCancelled?.invoke()
            var finished = false
            client = LinkedClientManager.getInstance().startNewClient(
                    lifetimeDef.lifetime,
                    URI(effectiveJoinLink),
                    "",
                    onConnected,
                    false
                )

            client.onClientPresenceChanged.advise(client.lifetime) { finished = true }
            client.clientClosed.advise(client.lifetime) { onDisconnected.invoke(); finished = true }
            client.clientFailedToOpenProject.advise(client.lifetime) { cleanup(); finished = true }

            val startTime = System.currentTimeMillis()
            val maxWaitMillis = 60 * 1000
            while (!finished) {
                checkCancelled?.invoke()
                check(System.currentTimeMillis() - startTime <= maxWaitMillis) { "Could not connect, remote IDE client is not ready." }
                Thread.sleep(200)
            }

            // Check if the thin client has opened
            check(client.clientPresent) { "Could not connect, remote IDE client is not ready." }

            onConnected.invoke()
            return client
        } catch (e: Exception) {
            cleanup()
            throw  e
        }
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }

    private fun closeForwarder(forwarder: Closeable?) {
        if (forwarder != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    forwarder.close()
                } catch (e: Throwable) {
                    thisLogger().debug("Failed to close port forwarder", e)
                }
            }
        }
    }

    private fun invokeOnDisconnected(onDisconnected: () -> Unit) {
        try { onDisconnected() } catch (_: Exception) { }
    }

    private fun stopDevWorkspace(
        remoteIdeServer: RemoteIDEServer?,
        devSpacesContext: DevSpacesContext,
        onDevWorkspaceStopped: () -> Unit
    ) {
        try {
            if (remoteIdeServer?.isServerState(false) == true) {
                DevWorkspaces(devSpacesContext.client)
                    .stop(
                        devSpacesContext.devWorkspace.namespace,
                        devSpacesContext.devWorkspace.name
                    )
                    .also { onDevWorkspaceStopped() }
            }
        } catch (e: Exception) {
            thisLogger().debug("Failed to stop DevWorkspace", e)
        }
    }

    @Throws(IOException::class, ApiException::class, CancellationException::class)
    private fun startAndWaitDevWorkspace(checkCancelled: (() -> Unit)? = null) {
        if (!devSpacesContext.devWorkspace.started) {
            checkCancelled?.invoke()
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
                    DevWorkspaces.RUNNING_TIMEOUT,
                    checkCancelled
                )
        ) throw IOException(
            "DevWorkspace '${devSpacesContext.devWorkspace.name}' is not running after ${DevWorkspaces.RUNNING_TIMEOUT} seconds"
        )
    }
}
