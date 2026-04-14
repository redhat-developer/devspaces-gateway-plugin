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
import com.redhat.devtools.gateway.util.ProgressCountdown
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.view.ui.Dialogs
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DevSpacesConnection(private val devSpacesContext: DevSpacesContext) {
    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    fun connect(
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        onProgress: ((value: ProgressCountdown.ProgressEvent) -> Unit)? = null,
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
        onProgress: ((value: ProgressCountdown.ProgressEvent) -> Unit)? = null,
        checkCancelled: (() -> Unit)? = null
    ): ThinClientHandle {
        val workspace = devSpacesContext.devWorkspace
        devSpacesContext.addWorkspace(workspace)

        var remoteIdeServer: RemoteIDEServer? = null
        var forwarder: Closeable? = null
        var client: ThinClientHandle? = null

        return try {
            var remoteIdeServerStatus: RemoteIDEServerStatus = RemoteIDEServerStatus.empty()
            while (!remoteIdeServerStatus.isReady) {
                checkCancelled?.invoke()
                onProgress?.invoke(ProgressCountdown.ProgressEvent(
                    message = "Waiting for the workspace to get ready...",
                    countdownSeconds = DevWorkspaces.RUNNING_TIMEOUT))

                DevWorkspaces(devSpacesContext.client)
                    .startAndWait(
                        devSpacesContext.devWorkspace.namespace,
                        devSpacesContext.devWorkspace.name,
                        checkCancelled = checkCancelled)

                checkCancelled?.invoke()
                onProgress?.invoke(ProgressCountdown.ProgressEvent(
                    message = "Waiting for the workspace to get ready...",
                    countdownSeconds = RemoteIDEServer.readyTimeout))

                remoteIdeServer = RemoteIDEServer(devSpacesContext)
                remoteIdeServerStatus = runCatching {
                    remoteIdeServer.apply { waitServerReady(checkCancelled) }.getStatus()
                }.getOrElse { e ->
                    if (e.isCancellationException()) throw e
                    RemoteIDEServerStatus.empty()
                }

                checkCancelled?.invoke()
                if (!remoteIdeServerStatus.isReady) {
                    val restartWorkspace = Dialogs.ideNotResponding()

                    if (restartWorkspace) {
                        // User chose "Restart Pod": stop the Pod and try starting from scratch
                        DevWorkspaces(devSpacesContext.client).stopAndWait(
                            devSpacesContext.devWorkspace.namespace,
                            devSpacesContext.devWorkspace.name,
                            checkCancelled = checkCancelled
                        )
                        continue
                    } else {
                        // User chose "Cancel Connection"
                        throw CancellationException("User cancelled the operation")
                    }
                }
            }

            checkCancelled?.invoke()
            val joinLink = remoteIdeServerStatus.joinLink
                ?: throw IOException("Could not connect, workspace IDE is not ready. No join link present.")

            check(remoteIdeServer != null)
            checkCancelled?.invoke()
            onProgress?.invoke(ProgressCountdown.ProgressEvent(
                message = "Waiting for the workspace IDE client to start..."))

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
                    delay(200.milliseconds)
                }
                true
            } ?: false

            // Check if the thin client has opened
            check(success && client.clientPresent) {
                "Could not connect, workspace IDE is not ready."
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
}
