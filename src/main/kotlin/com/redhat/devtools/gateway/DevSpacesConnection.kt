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
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.devworkspace.DevWorkspacePatch
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceRestart
import com.redhat.devtools.gateway.devworkspace.DevWorkspaces
import com.redhat.devtools.gateway.devworkspace.RestartDevWorkspaceAnnotationWatch
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import com.redhat.devtools.gateway.download.DevSpacesClientMirror
import com.redhat.devtools.gateway.download.DevSpacesClientConnectProgress
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.server.RemoteIDEServerStatus
import com.redhat.devtools.gateway.util.ProgressCountdown
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.util.isServerContainerNotFound
import com.redhat.devtools.gateway.view.ui.Dialogs
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.*
import java.io.Closeable
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin-client connection lifecycle.
 *
 * Thin-client close or failed-to-open sets connectFailed (ending the connect wait);
 * [tearDownConnection] runs only if the connection is already live ([connectionLive]).
 * Failures during connect are cleaned up by [connect]'s catch path.
 *
 * Connect enablement in the wizard is based on workspace Running state (not
 * [DevSpacesContext.activeWorkspaces]), because IDEA often keeps the connector view
 * after Guest close and thin-client signals are unreliable for UI gating.
 *
 * Still clear [DevSpacesContext.activeWorkspaces] when the connection ends (before optional
 * remote stop) for tooltips / bookkeeping.
 */
class DevSpacesConnection(private val devSpacesContext: DevSpacesContext) {

    companion object {
        private const val CONNECT_POLL: Long = 200 // millis
    }

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
        registerRestartWatcher: Boolean = true
    ): ThinClientHandle {
        val workspace = devSpacesContext.devWorkspace
        tearDownStarted.set(false)
        devSpacesContext.addWorkspace(workspace)

        var remoteIdeServer: RemoteIDEServer? = null
        var forwarder: Closeable? = null
        var client: ThinClientHandle? = null
        var clientMirror: AutoCloseable? = null
        val connectionLive = AtomicBoolean(false)
        val progress = DevSpacesClientConnectProgress(onProgress)

        return try {
            remoteIdeServer = waitUntilServerReady(checkCancelled, onProgress, modalityState)

            checkCancelled?.invoke()
            val joinLink = remoteIdeServer.getStatus(checkCancelled).joinLink
                ?: throw ThinClientNotReadyException(
                    "Could not connect, workspace IDE is not ready. No join link present.")

            checkCancelled?.invoke()
            progress.waitingForClientStart()

            val (fwd, localPort) = setupPortForwarding(remoteIdeServer.pod)
            forwarder = fwd

            val localJoinLink = joinLink.replace(":5990", ":$localPort")
            val connectFailed = AtomicBoolean(false)

            checkCancelled?.invoke()
            clientMirror = mirrorClient(URI(localJoinLink), progress, checkCancelled)
            client = startClient(
                URI(localJoinLink), workspace, onConnected, onConnectionEnded, onDevWorkspaceStopped,
                remoteIdeServer, forwarder, connectFailed, connectionLive
            )

            progress.startingClient()
            waitForConnect(client, connectFailed, checkCancelled)

            if (registerRestartWatcher) {
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
            if (e !is ThinClientLaunchException || connectionLive.get()) {
                runCatching { client?.close() }
            }
            tearDownConnection(
                client, workspace, onConnectionEnded, onDevWorkspaceStopped, remoteIdeServer, forwarder
            )
            throw e
        } finally {
            runCatching { clientMirror?.close() }
        }
    }

    /**
     * Thin client closed or failed to open.
     * Always ends the connect-wait loop. Calls [tearDownConnection] only if the connection is
     * already live; failures during connect are cleaned up by [connect]'s catch.
     */
    @Suppress("UnstableApiUsage")
    internal fun onThinClientClosed(
        connectFailed: AtomicBoolean,
        connectionLive: AtomicBoolean,
        thinClient: ThinClientHandle,
        workspace: DevWorkspace,
        onConnectionEnded: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        remoteIdeServer: RemoteIDEServer?,
        forwarder: Closeable?,
    ) {
        connectFailed.set(true)
        if (connectionLive.get()) {
            tearDownConnection(
                thinClient,
                workspace,
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
     * Uses the [workspace] captured at [connect] start so teardown stays correct if the wizard
     * selection changes while the Guest is open.
     *
     * Idempotent across thin-client close, connect failure, and duplicate signals.
     */
    @Suppress("UnstableApiUsage")
    private fun tearDownConnection(
        client: ThinClientHandle? = null,
        workspace: DevWorkspace,
        onConnectionEnded: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        remoteIdeServer: RemoteIDEServer?,
        forwarder: Closeable?
    ) {
        if (!tearDownStarted.compareAndSet(false, true)) {
            return
        }
        // Clear tracking + refresh UI before any remote wait so Connect is not stuck
        // behind waitServerTerminated (up to 10s) when the wizard stays open in IDEA.
        devSpacesContext.removeWorkspace(workspace)
        runCatching { onConnectionEnded() }

        CoroutineScope(Dispatchers.IO).launch {
            runCatching { client?.close() }
            val workspacePatch = DevWorkspacePatch(
                workspace.namespace,
                workspace.name,
                devSpacesContext.client
            ) {
                DevWorkspaces(devSpacesContext.client).get(workspace.namespace, workspace.name)
            }
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
                message = "",
                countdownSeconds = DevWorkspaces.RUNNING_TIMEOUT))

            DevWorkspaces(devSpacesContext.client)
                .startAndWait(
                    devSpacesContext.devWorkspace.namespace,
                    devSpacesContext.devWorkspace.name,
                    checkCancelled = checkCancelled)

            checkCancelled?.invoke()
            onProgress?.invoke(ProgressCountdown.ProgressEvent(
                title = "Waiting for the workspace to get ready...",
                countdownSeconds = RemoteIDEServer.readyTimeout)
            )

            remoteIdeServer = RemoteIDEServer(devSpacesContext)
            remoteIdeServerStatus = runCatching {
                remoteIdeServer.apply { waitServerReady(checkCancelled) }.getStatus(checkCancelled)
            }.getOrElse { e ->
                if (e.isCancellationException()) throw e
                // no idea-server container, don't offer "restart pod" (CRW-11897).
                if (e.isServerContainerNotFound()) throw e
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

    /**
     * Ensures the JetBrains Client referenced by the join link is available locally
     * before the thin client is started, and activates the mirror for this connect.
     *
     * The platform thin-client launcher downloads the client without resume support: on
     * unstable links (VPN, corporate proxies) a 700+ MB first-time download is regularly
     * cut mid-transfer, and all its 5 attempts - each restarting from byte 0 - fail,
     * which used to surface as "workspace IDE is not ready" although the download URL
     * works fine in a browser. Mirroring the client with resume support first lets the
     * platform copy it from local disk instead. Download failures are reported as
     * [ThinClientDownloadException] so the wizard can state the actual cause.
     */
    internal suspend fun mirrorClient(
        localJoinLink: URI,
        progress: DevSpacesClientConnectProgress,
        checkCancelled: (() -> Unit)?,
    ): AutoCloseable = try {
        DevSpacesClientMirror().ensureMirrored(localJoinLink, progress, checkCancelled)
    } catch (e: Exception) {
        if (e.isCancellationException()) throw e
        throw ThinClientDownloadException("Could not download the JetBrains Client: ${e.message}", e)
    }

    @Suppress("UnstableApiUsage")
    private fun startClient(
        localJoinLink: URI,
        workspace: DevWorkspace,
        onConnected: () -> Unit,
        onConnectionEnded: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        remoteIdeServer: RemoteIDEServer?,
        forwarder: Closeable?,
        connectFailed: AtomicBoolean,
        connectionLive: AtomicBoolean,
    ): ThinClientHandle {
        val thinClient = LinkedClientManager
            .getInstance()
            .startNewClient(
                Lifetime.Eternal,
                localJoinLink,
                "",
                onConnected,
                false
            )

        fun notifyThinClientClosed() {
            onThinClientClosed(
                connectFailed,
                connectionLive,
                thinClient,
                workspace,
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

    /**
     * Waits for the JetBrains Thin Client to become available.
     *
     * The first-time JetBrains Client download may take several minutes. This phase waits
     * until [client.clientPresent] is true, or for a permanent failure signalled via
     * [connectFailed] (clientClosed / clientFailedToOpenProject). There is no wall-clock
     * timeout on this wait.
     *
     * A permanent failure is reported as [ThinClientLaunchException] — the client was
     * started but closed or failed to open the project, e.g. because the platform's own
     * download or extraction failed (details are in the IDE log and the platform's error
     * notification); the workspace IDE itself is not at fault.
     */
    @Suppress("UnstableApiUsage")
    internal suspend fun waitForConnect(
        client: ThinClientHandle,
        connectFailed: AtomicBoolean,
        checkCancelled: (() -> Unit)?
    ) {
        while (!client.clientPresent && !connectFailed.get()) {
            checkCancelled?.invoke()
            @Suppress("ConvertLongToDuration")
            delay(CONNECT_POLL)
        }

        if (!client.clientPresent || connectFailed.get()) {
            throw ThinClientLaunchException(
                "Could not start the JetBrains Client. See the IDE log for details."
            )
        }
    }
}
