/*
 * Copyright (c) 2024-2026 Red Hat, Inc.
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
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.redhat.devtools.gateway.devworkspace.DevWorkspacePatch
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceRestart
import com.redhat.devtools.gateway.devworkspace.DevWorkspaces
import com.redhat.devtools.gateway.devworkspace.RestartDevWorkspaceAnnotationWatch
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.server.RemoteIDEServerStatus
import com.redhat.devtools.gateway.util.ProgressCountdown
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.view.ui.Dialogs
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin-client connection lifecycle.
 *
 * Thin-client close always ends the connect wait; [tearDownConnection] runs only if the
 * connection is already live ([connectionLive]). Failures during connect are cleaned up by
 * [connect]'s catch path.
 *
 * Connect enablement in the wizard is based on workspace Running state (not
 * [DevSpacesContext.activeWorkspaces]), because IDEA often keeps the connector view
 * after Guest close and thin-client signals are unreliable for UI gating.
 *
 * Still clear [DevSpacesContext.activeWorkspaces] when the connection ends (before optional
 * remote stop) for tooltips / bookkeeping.
 */
class DevSpacesConnection(private val devSpacesContext: DevSpacesContext) {
    /** Ensures [tearDownConnection] runs at most once for this connect attempt. */
    private val tearDownStarted = AtomicBoolean(false)

    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    suspend fun connect(
        onConnected: () -> Unit,
        onConnectionEnded: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        onProgress: ((value: ProgressCountdown.ProgressEvent) -> Unit)? = null,
        checkCancelled: (() -> Unit)? = null,
        modalityState: ModalityState? = null,
        registerRestartWatcher: Boolean? = true
    ): ThinClientHandle {
        val workspace = devSpacesContext.devWorkspace
        tearDownStarted.set(false)
        devSpacesContext.addWorkspace(workspace)

        var remoteIdeServer: RemoteIDEServer? = null
        var forwarder: Closeable? = null
        var client: ThinClientHandle? = null
        val connectionLive = AtomicBoolean(false)

        return try {
            remoteIdeServer = waitUntilServerReady(checkCancelled, onProgress, modalityState)

            checkCancelled?.invoke()
            val joinLink = remoteIdeServer.getStatus(checkCancelled).joinLink
                ?: throw IOException("Could not connect, workspace IDE is not ready. No join link present.")

            checkCancelled?.invoke()
            onProgress?.invoke(ProgressCountdown.ProgressEvent(
                message = "Waiting for the workspace IDE client to start..."))

            val (fwd, localPort) = setupPortForwarding(remoteIdeServer.pod)
            forwarder = fwd

            val effectiveJoinLink = joinLink.replace(":5990", ":$localPort")
            val connectWaitDone = AtomicBoolean(false)

            checkCancelled?.invoke()
            client = startThinClient(
                URI(effectiveJoinLink), onConnected, onConnectionEnded, onDevWorkspaceStopped,
                remoteIdeServer, forwarder, connectWaitDone, connectionLive
            )

            waitForThinClientConnect(client, connectWaitDone, checkCancelled)

            if (registerRestartWatcher == true) {
                watchRestartAnnotation(
                    workspace.namespace,
                    workspace.name,
                    devSpacesContext.client,
                    client
                )
            }

            connectionLive.set(true)
            onConnected()
            client
        } catch (e: Exception) {
            runCatching { client?.close() }
            tearDownConnection(client, onConnectionEnded, onDevWorkspaceStopped, remoteIdeServer, forwarder)
            throw e
        }
    }

    /**
     * Thin client closed or failed to open.
     * Always ends the connect-wait loop. Calls [tearDownConnection] only if the connection is
     * already live; failures during connect are cleaned up by [connect]'s catch.
     */
    @Suppress("UnstableApiUsage")
    private fun onThinClientClosed(
        connectWaitDone: AtomicBoolean,
        connectionLive: AtomicBoolean,
        thinClient: ThinClientHandle,
        onConnectionEnded: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        remoteIdeServer: RemoteIDEServer?,
        forwarder: Closeable?,
    ) {
        connectWaitDone.set(true)
        if (connectionLive.get()) {
            tearDownConnection(
                thinClient,
                onConnectionEnded,
                onDevWorkspaceStopped,
                remoteIdeServer,
                forwarder
            )
        }
    }

    @Suppress("UnstableApiUsage")
    private fun watchRestartAnnotation(
        namespace: String,
        workspaceName: String,
        kubeClient: ApiClient,
        thinClient: ThinClientHandle
    ) {
        val restartWatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        RestartDevWorkspaceAnnotationWatch(
            onRestartAnnotated(thinClient),
            kubeClient,
            namespace,
            workspaceName
        ).start(restartWatchScope)

        thinClient.lifetime.onTermination {
            restartWatchScope.cancel()
        }
    }

    @Suppress("UnstableApiUsage")
    private fun onRestartAnnotated(
        thinClient: ThinClientHandle
    ): () -> Job {
        return {
            CoroutineScope(Dispatchers.IO).launch {
                DevWorkspaceRestart(devSpacesContext).execute(thinClient)
            }
        }
    }

    /**
     * Ends the local connection: clear "already connected" tracking, notify the UI, then
     * optionally stop the DevWorkspace (restart annotation / Close and Stop).
     *
     * Idempotent across thin-client close, connect failure, and duplicate signals.
     */
    @Suppress("UnstableApiUsage")
    private fun tearDownConnection(
        client: ThinClientHandle? = null,
        onConnectionEnded: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        remoteIdeServer: RemoteIDEServer?,
        forwarder: Closeable?
    ) {
        if (!tearDownStarted.compareAndSet(false, true)) {
            return
        }
        val workspace = devSpacesContext.devWorkspace
        // Clear tracking + refresh UI before any remote wait so Connect is not stuck
        // behind waitServerTerminated (up to 10s) when the wizard stays open in IDEA.
        devSpacesContext.removeWorkspace(workspace)
        runCatching { onConnectionEnded() }

        CoroutineScope(Dispatchers.IO).launch {
            runCatching { client?.close() }
            val workspacePatch = DevWorkspacePatch(
                workspace.namespace,
                workspace.name,
                devSpacesContext.client,
                {
                    DevWorkspaces(devSpacesContext.client).get(workspace.namespace, workspace.name)
                }
            )
            try {
                if (workspacePatch.hasRestartAnnotation()) {
                    closeAllProjects()
                } else if (true == remoteIdeServer?.waitServerTerminated()) {
                    DevWorkspaces(devSpacesContext.client)
                        .stop(workspace.namespace, workspace.name)
                        .also { onDevWorkspaceStopped() }
                }
            } finally {
                runCatching { forwarder?.close() }
                    .onFailure { e -> thisLogger().debug("Failed to close port forwarder", e) }
            }
        }
    }

    private fun closeAllProjects() {
        ApplicationManager.getApplication().invokeLater(
            {
                val pm = ProjectManagerEx.getInstanceEx()
                for (project in pm.openProjects.toList()) {
                    if (!project.isDisposed) {
                        pm.closeAndDispose(project)
                    }
                }
            },
            ModalityState.nonModal()
        )
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }

    /**
     * Handles the case where the remote IDE server is not ready after starting the workspace.
     * Shows a dialog and either restarts the workspace (return true) or indicates cancellation (return false).
     */
    private fun handleServerNotReady(
        checkCancelled: (() -> Unit)?,
        modalityState: ModalityState?
    ): Boolean {
        val restartWorkspace = Dialogs.ideNotResponding(modalityState)
        if (restartWorkspace) {
            DevWorkspaces(devSpacesContext.client).stopAndWait(
                devSpacesContext.devWorkspace.namespace,
                devSpacesContext.devWorkspace.name,
                checkCancelled = checkCancelled
            )
        }
        return restartWorkspace
    }

    private suspend fun waitUntilServerReady(
        checkCancelled: (() -> Unit)?,
        onProgress: ((ProgressCountdown.ProgressEvent) -> Unit)?,
        modalityState: ModalityState?
    ): RemoteIDEServer {
        var remoteIdeServerStatus: RemoteIDEServerStatus = RemoteIDEServerStatus.empty()
        var remoteIdeServer: RemoteIDEServer? = null

        while (!remoteIdeServerStatus.isReady) {
            checkCancelled?.invoke()
            onProgress?.invoke(ProgressCountdown.ProgressEvent(
                message = "Waiting for the workspace to get started...",
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
                remoteIdeServer.apply { waitServerReady(checkCancelled) }.getStatus(checkCancelled)
            }.getOrElse { e ->
                if (e.isCancellationException()) throw e
                RemoteIDEServerStatus.empty()
            }

            checkCancelled?.invoke()
            if (!remoteIdeServerStatus.isReady) {
                if (handleServerNotReady(checkCancelled, modalityState)) {
                    continue
                } else {
                    throw CancellationException("User cancelled the operation")
                }
            }
        }
        return remoteIdeServer!!
    }

    private fun setupPortForwarding(pod: V1Pod): Pair<Closeable, Int> {
        val pods = DevWorkspacePods(devSpacesContext.client)
        val localPort = findFreePort()
        val forwarder = pods.forward(pod, localPort, 5990)
        pods.waitForForwardReady(localPort)
        return forwarder to localPort
    }

    @Suppress("UnstableApiUsage")
    private fun startThinClient(
        effectiveJoinLink: URI,
        onConnected: () -> Unit,
        onConnectionEnded: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        remoteIdeServer: RemoteIDEServer?,
        forwarder: Closeable?,
        connectWaitDone: AtomicBoolean,
        connectionLive: AtomicBoolean,
    ): ThinClientHandle {
        val thinClient = LinkedClientManager
            .getInstance()
            .startNewClient(
                Lifetime.Eternal,
                effectiveJoinLink,
                "",
                onConnected,
                false
            )

        thinClient.onClientPresenceChanged.advise(thinClient.lifetime) { connectWaitDone.set(true) }

        fun notifyThinClientClosed() {
            onThinClientClosed(
                connectWaitDone,
                connectionLive,
                thinClient,
                onConnectionEnded,
                onDevWorkspaceStopped,
                remoteIdeServer,
                forwarder
            )
        }
        thinClient.clientClosed.advise(thinClient.lifetime) { notifyThinClientClosed() }
        thinClient.clientFailedToOpenProject.advise(thinClient.lifetime) { notifyThinClientClosed() }

        return thinClient
    }

    private suspend fun waitForThinClientConnect(
        thinClient: ThinClientHandle,
        connectWaitDone: AtomicBoolean,
        checkCancelled: (() -> Unit)?
    ) {
        @Suppress("ConvertLongToDuration")
        val success = withTimeoutOrNull(60_000L) {
            while (!connectWaitDone.get()) {
                checkCancelled?.invoke()
                delay(200L)
            }
            true
        } ?: false

        check(success && thinClient.clientPresent) {
            "Could not connect, workspace IDE is not ready."
        }
    }
}
