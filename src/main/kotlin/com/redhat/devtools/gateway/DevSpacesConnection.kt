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
import com.intellij.openapi.ui.Messages
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.redhat.devtools.gateway.openshift.DevWorkspaces
import com.redhat.devtools.gateway.openshift.Pods
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.server.RemoteIDEServerStatus
import io.kubernetes.client.openapi.ApiException
import okio.Closeable
import java.io.IOException
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class DevSpacesConnection(private val devSpacesContext: DevSpacesContext) {
    @Throws(Exception::class, CancellationException::class)
    @Suppress("UnstableApiUsage")
    fun connect(
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        onProgress: ((message: String) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): ThinClientHandle {
        if (devSpacesContext.isConnected)
            throw IOException(String.format("Already connected to %s", devSpacesContext.devWorkspace.metadata.name))

        devSpacesContext.isConnected = true
        try {
            return doConnection(onConnected, onDevWorkspaceStopped, onDisconnected, onProgress, isCancelled)
        } catch (e: Exception) {
            devSpacesContext.isConnected = false
            throw e
        }
    }

    @Throws(Exception::class, CancellationException::class)
    @Suppress("UnstableApiUsage")
    private fun doConnection(
        onConnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        onDisconnected: () -> Unit,
        onProgress: ((message: String) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): ThinClientHandle {
        startAndWaitDevWorkspace(onProgress)
        if (isCancelled?.invoke() == true) {
            throw CancellationException("User cancelled the operation")
        }

        onProgress?.invoke("Waiting for the Remote IDE server to get ready...")
        val (remoteIdeServer, remoteIdeServerStatus) =
            try {
                val remoteIdeServer = RemoteIDEServer(devSpacesContext).apply {
                    waitRemoteIDEServerReady()
                }
                remoteIdeServer to remoteIdeServer.getStatus()
            } catch (_: IOException) {
                null to RemoteIDEServerStatus.empty()
            }

        if (isCancelled?.invoke() == true) {
            throw CancellationException("User cancelled the operation")
        }

        if (remoteIdeServer == null || !remoteIdeServerStatus.isReady) {
            thisLogger().debug("Remote IDE server is in an invalid state. Please restart the pod and try again. ")
            val result = AtomicInteger(-1)
            ApplicationManager.getApplication().invokeAndWait {
                result.set(
                    Messages.showDialog(
                        "The Remote IDE Server is not responding properly.\n" +
                                "Would you like to try restarting the Pod or cancel the connection?",
                        "Remote IDE Server Issue",
                        arrayOf("Cancel Connection", "Restart Pod and try again"),
                        0,  // default selected index
                        Messages.getWarningIcon()
                    )
                )
            }

            when (result.get()) {
                1 -> {
                    // User chose "Restart Pod"
                    thisLogger().info("User chose to restart the pod.")
                    stopAndWaitDevWorkspace(onProgress)
                    if (isCancelled?.invoke() == true) {
                        throw CancellationException("User cancelled the operation")
                    }
                    return doConnection(onConnected, onDevWorkspaceStopped, onDisconnected, onProgress, isCancelled)
                }
            }

            // User chose "Cancel Connection"
            thisLogger().info("User cancelled the remote IDE connection.")
            throw IllegalStateException("Remote IDE server is not responding properly. Try restarting the pod and reconnecting.")
        }

        val client = LinkedClientManager
            .getInstance()
            .startNewClient(
                Lifetime.Eternal,
                URI(remoteIdeServerStatus.joinLink!!),
                "",
                onConnected,
                false
            )

        val forwarder = Pods(devSpacesContext.client).forward(remoteIdeServer.pod, 5990, 5990)
        try {
            client.run {
                lifetime.onTermination {
                    cleanup(forwarder, remoteIdeServer, devSpacesContext, onDevWorkspaceStopped, onDisconnected)
                }
            }
        } catch (e: Exception) {
            cleanup(forwarder, remoteIdeServer, devSpacesContext, onDevWorkspaceStopped, onDisconnected)
            throw e // rethrow so caller can handle the original problem
        }

        return client
    }

    private fun cleanup(
        forwarder: Closeable?,
        remoteIdeServer: RemoteIDEServer?,
        devSpacesContext: DevSpacesContext,
        onDevWorkspaceStopped: () -> Unit,
        onDisconnected: () -> Unit
    ) {
        try {
            forwarder?.close()
            thisLogger().info("Closed port forwarder")
        } catch (e: Exception) {
            thisLogger().debug("Failed to close port forwarder", e)
        }

        try {
            if (remoteIdeServer?.isRemoteIdeServerState(false) == true) {
                DevWorkspaces(devSpacesContext.client)
                    .stop(
                        devSpacesContext.devWorkspace.metadata.namespace,
                        devSpacesContext.devWorkspace.metadata.name
                    )
                    .also { onDevWorkspaceStopped() }
            }
        } catch (e: Exception) {
            thisLogger().debug("Failed to stop DevWorkspace", e)
        }

        devSpacesContext.isConnected = false

        try {
            onDisconnected()
        } catch (e: Exception) {
            thisLogger().debug("onDisconnected handler failed", e)
        }
    }


    @Throws(IOException::class, ApiException::class, CancellationException::class)
    private fun startAndWaitDevWorkspace(onProgress: ((message: String) -> Unit)? = null,
                                         isCancelled: (() -> Boolean)? = null) {
        // We really need a refreshed DevWorkspace here
        val devWorkspace = DevWorkspaces(devSpacesContext.client).get(
            devSpacesContext.devWorkspace.metadata.namespace,
            devSpacesContext.devWorkspace.metadata.name)

        if (!devWorkspace.spec.started) {
            DevWorkspaces(devSpacesContext.client)
                .start(
                    devSpacesContext.devWorkspace.metadata.namespace,
                    devSpacesContext.devWorkspace.metadata.name
                )
        }

        if (!DevWorkspaces(devSpacesContext.client)
                .waitForPhase(
                    devSpacesContext.devWorkspace.metadata.namespace,
                    devSpacesContext.devWorkspace.metadata.name,
                    DevWorkspaces.RUNNING,
                    onProgress = { phase, message ->
                        onProgress?.invoke(buildString {
                            append("Phase: $phase")
                            if (message.isNotBlank()) append(" – $message")
                        })
                    },
                    isCancelled = { isCancelled?.invoke() ?: false }
                )
        ) throw IOException(
            String.format(
                "DevWorkspace '%s' is not running after %d seconds",
                devSpacesContext.devWorkspace.metadata.name,
                DevWorkspaces.RUNNING_TIMEOUT
            )
        )
    }

    @Throws(IOException::class, ApiException::class, CancellationException::class)
    private fun stopAndWaitDevWorkspace(onProgress: ((message: String) -> Unit)? = null,
                                        isCancelled: (() -> Boolean)? = null) {
        // We really need a refreshed DevWorkspace here
        val devWorkspace = DevWorkspaces(devSpacesContext.client).get(
            devSpacesContext.devWorkspace.metadata.namespace,
            devSpacesContext.devWorkspace.metadata.name)

        if (devWorkspace.spec.started) {
            DevWorkspaces(devSpacesContext.client)
                .stop(
                    devSpacesContext.devWorkspace.metadata.namespace,
                    devSpacesContext.devWorkspace.metadata.name
                )
        }

        if (!DevWorkspaces(devSpacesContext.client)
                .waitForPhase(
                    devSpacesContext.devWorkspace.metadata.namespace,
                    devSpacesContext.devWorkspace.metadata.name,
                    DevWorkspaces.STOPPED,
                    onProgress = { phase, message ->
                        onProgress?.invoke(buildString {
                            append("Phase: $phase")
                            if (message.isNotBlank()) append(" – $message")
                        })
                    },
                    isCancelled = { isCancelled?.invoke() ?: false }
                )
        ) throw IOException(
            String.format(
                "DevWorkspace '%s' is not stopped after %d seconds",
                devSpacesContext.devWorkspace.metadata.name,
                DevWorkspaces.RUNNING_TIMEOUT
            )
        )
    }
}
