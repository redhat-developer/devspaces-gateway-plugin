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

import com.intellij.openapi.diagnostic.thisLogger
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.redhat.devtools.gateway.openshift.DevWorkspaces
import com.redhat.devtools.gateway.openshift.Pods
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.server.RemoteIDEServerStatus
import io.kubernetes.client.openapi.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class DevSpacesConnection(private val devSpacesContext: DevSpacesContext) {
    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    fun connect(
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        onProgress: ((message: String) -> Unit)? = null,
        checkCancelled: (() -> Unit)? = null
    ): ThinClientHandle = runBlocking {
        doConnect(onConnected, onDevWorkspaceStopped, onDisconnected, onProgress, checkCancelled)
    }

    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    private suspend fun doConnect(
        onConnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        onDisconnected: () -> Unit,
        onProgress: ((message: String) -> Unit)? = null,
        checkCancelled: (() -> Unit)? = null
    ): ThinClientHandle {
        val workspace = devSpacesContext.devWorkspace
        devSpacesContext.addWorkspace(workspace)

        var remoteIdeServer: RemoteIDEServer? = null
        var forwarder: Closeable? = null
        var client: ThinClientHandle? = null

        return try {
            onProgress?.invoke("Waiting for the Dev Workspace to get ready...")

            startAndWaitDevWorkspace()

            checkCancelled?.invoke()
            onProgress?.invoke("Waiting for the Remote IDE server to get ready...")

            remoteIdeServer = RemoteIDEServer(devSpacesContext)
            val remoteIdeServerStatus = runCatching {
                    val server = remoteIdeServer.apply { waitServerReady(checkCancelled) }
                    server.getStatus()
                }.getOrElse { RemoteIDEServerStatus.empty() }

            check(remoteIdeServerStatus.isReady) { "Could not connect, remote IDE is not ready." }

            val joinLink = remoteIdeServerStatus.joinLink
                ?: throw IOException("Could not connect, remote IDE is not ready. No join link present.")

            checkCancelled?.invoke()
            onProgress?.invoke("Waiting for the IDE client to start up...")

            val pods = Pods(devSpacesContext.client)
            val localPort = findFreePort()
            forwarder = pods.forward(remoteIdeServer.pod, localPort, 5990)
            pods.waitForForwardReady(localPort)

            val effectiveJoinLink = joinLink.replace(":5990", ":$localPort")

            val lifetimeDef = Lifetime.Eternal.createNested()
            lifetimeDef.lifetime.onTermination { onClientClosed( client, onDisconnected, onDevWorkspaceStopped, remoteIdeServer, forwarder) }

            val finished = AtomicBoolean(false)

            checkCancelled?.invoke()

            client = LinkedClientManager
                .getInstance()
                .startNewClient(
                    Lifetime.Eternal,
                    URI(effectiveJoinLink),
                    "",
                    onConnected, // Triggers enableButtons() via view
                    false
                )

            client.onClientPresenceChanged.advise(client.lifetime) { finished.set(true) }
            client.clientClosed.advise(client.lifetime) {
                onClientClosed(client, onDisconnected, onDevWorkspaceStopped, remoteIdeServer, forwarder)
                finished.set(true)
            }
            client.clientFailedToOpenProject.advise(client.lifetime) {
                onClientClosed(client, onDisconnected, onDevWorkspaceStopped, remoteIdeServer, forwarder)
                finished.set(true)
            }

            val success = withTimeoutOrNull(60.seconds) {
                while (!finished.get()) {
                    checkCancelled?.invoke()
                    delay(200)
                }
                true
            } ?: false

            // Check if the thin client has opened
            check(success && client.clientPresent) {
                "Could not connect, remote IDE client is not ready."
            }

            onConnected()
            client
        } catch (e: Exception) {
            runCatching { client?.close() }
            onClientClosed(client, onDisconnected, onDevWorkspaceStopped, remoteIdeServer, forwarder)
            throw e
        }
    }

    @Suppress("UnstableApiUsage")
    private fun onClientClosed(
        client: ThinClientHandle? = null,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        remoteIdeServer: RemoteIDEServer?,
        forwarder: Closeable?
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { client?.close() }
            val currentWorkspace = devSpacesContext.devWorkspace
            try {
                if (true == remoteIdeServer?.waitServerTerminated()) {
                    DevWorkspaces(devSpacesContext.client)
                        .stop(
                            devSpacesContext.devWorkspace.namespace,
                            devSpacesContext.devWorkspace.name
                        )
                        .also { onDevWorkspaceStopped() }
                }
            } finally {
                runCatching {
                    forwarder?.close()
                }.onFailure { e ->
                    thisLogger().debug("Failed to close port forwarder", e)
                }
                devSpacesContext.removeWorkspace(currentWorkspace)
                runCatching { onDisconnected() }
            }
        }
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
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

        if (!runBlocking { DevWorkspaces(devSpacesContext.client)
                .waitPhase(
                    devSpacesContext.devWorkspace.namespace,
                    devSpacesContext.devWorkspace.name,
                    DevWorkspaces.RUNNING,
                    DevWorkspaces.RUNNING_TIMEOUT,
                    checkCancelled
            ) }
        ) throw IOException(
            "DevWorkspace '${devSpacesContext.devWorkspace.name}' is not running after ${DevWorkspaces.RUNNING_TIMEOUT} seconds"
        )
    }
}
