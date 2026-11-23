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

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.devworkspace.DevWorkspacePatch
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceRestart
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceSessionWatch
import com.redhat.devtools.gateway.devworkspace.DevWorkspaces
import com.redhat.devtools.gateway.download.DevSpacesClientConnectProgress
import com.redhat.devtools.gateway.download.DevSpacesClientMirror
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import com.redhat.devtools.gateway.openshift.OpenPortForward
import com.redhat.devtools.gateway.openshift.PodPortForwarder
import com.redhat.devtools.gateway.openshift.PortForwardSession
import com.redhat.devtools.gateway.openshift.ProbePortForward
import com.redhat.devtools.gateway.recovery.ThinClientRecovery
import com.redhat.devtools.gateway.recovery.ThinClientRecoveryWatcher
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.server.RemoteIDEServerStatus
import com.redhat.devtools.gateway.util.ProgressCountdown
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.util.isServerContainerNotFound
import com.redhat.devtools.gateway.view.ui.Dialogs
import kotlinx.coroutines.*
import java.net.URI
import java.util.concurrent.CancellationException

/**
 * Thin-client connection lifecycle.
 *
 * Each [connect] call owns one [ConnectAttempt], which holds the per-attempt flags and resources.
 * One thin-client close / failed-to-open callback serves both the handshake and the live session.
 * It always ends the attempt's connect wait; [tearDownConnection] runs only if the session is
 * already live. Failures during connect are cleaned up by [connect]'s catch path.
 *
 * Connect enablement in the wizard is based on workspace Running state (not
 * [DevSpacesContext.activeWorkspaces]), because IDEA often keeps the connector view
 * after Guest close and thin-client signals are unreliable for UI gating.
 *
 * Still clear [DevSpacesContext.activeWorkspaces] when the connection ends (before optional
 * remote stop) for tooltips / bookkeeping.
 *
 * A live connection also watches its DevWorkspace: a restart annotation from the remote IDE
 * restarts it, and a stop (Dashboard or Gateway) cuts the bridged connections at once so the Guest
 * reports the loss without waiting for the pod to terminate. The stop keeps recovery armed and the
 * forward listening: a workspace started again reconnects the Guest (CRW-13910).
 */
@Suppress("TooManyFunctions") // the connection lifecycle is one cohesive operation
class DevSpacesConnection internal constructor(
    private val devSpacesContext: DevSpacesContext,
    private val openPortForward: OpenPortForward,
    private val probePortForward: ProbePortForward
) {

    constructor(
        devSpacesContext: DevSpacesContext
    ) : this(devSpacesContext, PodPortForwarder(devSpacesContext.client))

    /** Wires both seams to one forwarder, so a forward and its recovery probes share its state. */
    private constructor(
        devSpacesContext: DevSpacesContext,
        forwarder: PodPortForwarder
    ) : this(
        devSpacesContext,
        openPortForward = { pod, remotePort, resolvePod, onRemoteEnd ->
            forwarder.forward(pod, remotePort, resolvePod, onRemoteEnd)
        },
        probePortForward = { pod, remotePort ->
            forwarder.probe(pod, remotePort)
        }
    )

    companion object {
        private const val CONNECT_POLL: Long = 200 // millis

        private const val WAITING_FOR_WORKSPACE_TITLE = "Waiting for the workspace to get ready..."
    }

    /**
     * The attempt in flight. Recovery reads its teardown guard so a Guest close during recovery
     * still withholds the workspace auto-stop (§7.9).
     */
    private var currentAttempt: ConnectAttempt? = null

    /**
     * Recovery wiring of this session. Set up once the session is live; [tearDownConnection] reads
     * its state to withhold the workspace auto-stop (§7.9) and the forwarder's remote-end signal
     * starts it.
     */
    private val recovery = ThinClientRecovery(
        devSpacesContext,
        probePortForward,
        isTearingDown = { currentAttempt?.tearDownStarted?.get() == true }
    )

    @Throws(Exception::class)
    @Suppress("UnstableApiUsage", "LongParameterList") // this is the connection API surface
    suspend fun connect(
        onConnected: () -> Unit,
        onConnectionEnded: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        onProgress: ((value: ProgressCountdown.ProgressEvent) -> Unit)? = null,
        checkCancelled: (() -> Unit)? = null,
        modalityState: ModalityState? = null,
        registerRestartWatcher: Boolean = true,
        workspace: DevWorkspace? = null
    ): ThinClientHandle {
        val workspace = workspace ?: devSpacesContext.devWorkspace
        recovery.stopRecovery()
        devSpacesContext.addWorkspace(workspace)

        val attempt = ConnectAttempt(workspace, onConnectionEnded, onDevWorkspaceStopped)
        currentAttempt = attempt
        val progress = DevSpacesClientConnectProgress(onProgress)
        var clientMirror: AutoCloseable? = null

        return try {
            val remoteIdeServer = waitUntilServerReady(workspace, checkCancelled, onProgress, modalityState)
            attempt.remoteIdeServer = remoteIdeServer

            checkCancelled?.invoke()
            val joinLink = remoteIdeServer.getStatus(checkCancelled).joinLink
                ?: throw ThinClientNotReadyException(
                    "Could not connect, workspace IDE is not ready. No join link present.")

            checkCancelled?.invoke()
            progress.waitingForClientStart()

            val portForwardSession = startPortForward(attempt, workspace, remoteIdeServer)
            attempt.forwarder = portForwardSession

            val localJoinLink = ThinClientJoinLink.toLocal(URI(joinLink), portForwardSession.localPort)

            checkCancelled?.invoke()
            clientMirror = mirrorClient(localJoinLink, progress, checkCancelled)
            val thinClient = startClient(localJoinLink, attempt, onConnected)

            progress.startingClient()
            waitForConnect(attempt, checkCancelled)

            attempt.setSessionLive(true)
            portForwardSession.markEstablished()
            onConnected()
            watchSession(workspace, thinClient, portForwardSession, handleRestarts = registerRestartWatcher)
            // pod the local join link was minted against, stored as the session's pod identity so a
            // rolled pod reads as a stale link
            recovery.prepareRecovery(
                workspace, thinClient, portForwardSession, localJoinLink, remoteIdeServer.pod
            )
            thinClient
        } catch (e: Exception) {
            if (e !is ThinClientLaunchException || attempt.isSessionLive()) {
                runCatching { attempt.thinClient?.close() }
            }
            tearDownConnection(attempt)
            throw e
        } finally {
            runCatching { clientMirror?.close() }
        }
    }

    /**
     * Thin client closed or failed to open.
     *
     * Always sets [ConnectAttempt.connectWaitEnded], which ends the connect wait while the handshake
     * is still in progress; once the session is live nothing reads it anymore. Calls
     * [tearDownConnection] only if the session is already live; failures during connect are cleaned
     * up by [connect]'s catch.
     */
    internal fun onThinClientClosed(attempt: ConnectAttempt) {
        attempt.connectWaitEnded.set(true)
        if (attempt.isSessionLive()) {
            tearDownConnection(attempt)
        }
    }

    /**
     * Starts the port forward of [attempt].
     *
     * The forward outlives the client handle creation, so it resolves the handle lazily. The
     * resolver lets the forward follow a replacement pod: while the DevWorkspace stays Running,
     * the workspace - not the pod pinned here - is what keeps serving.
     */
    private fun startPortForward(
        attempt: ConnectAttempt,
        workspace: DevWorkspace,
        remoteIdeServer: RemoteIDEServer
    ): PortForwardSession =
        openPortForward(
            remoteIdeServer.pod,
            ThinClientJoinLink.REMOTE_IDE_PORT,
            {
                DevWorkspacePods(devSpacesContext.client)
                    .findServingPod(workspace.namespace, workspace.name)
            }
        ) {
            onRemoteEnd(attempt.thinClient)
        }

    /**
     * Watches the workspace that backs this session for the user actions around it: a restart
     * annotation set by the remote IDE restarts it, and a stop requested from Dashboard or Gateway
     * reports the loss at once ([onWorkspaceStopped]). One watch stream serves both; see
     * [DevWorkspaceSessionWatch].
     */
    @Suppress("UnstableApiUsage")
    private fun watchSession(
        workspace: DevWorkspace,
        thinClient: ThinClientHandle,
        forwarder: PortForwardSession,
        handleRestarts: Boolean
    ) {
        val sessionWatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val workspaces = DevWorkspaces(devSpacesContext.client)
        val sessionWatch = DevWorkspaceSessionWatch(
            namespace = workspace.namespace,
            workspaceName = workspace.name,
            readWorkspace = { workspaces.get(workspace.namespace, workspace.name) },
            createWatcher = { latestResourceVersion ->
                workspaces.createWatcher(
                    namespace = workspace.namespace,
                    fieldSelector = DevWorkspaces.nameSelector(workspace.name),
                    latestResourceVersion = latestResourceVersion
                )
            },
            onRestartAnnotated = if (handleRestarts) onRestartAnnotated(thinClient, workspace) else null,
            removeRestartAnnotations = {
                workspaces.removeRestartRequest(workspace.namespace, workspace.name)
            },
            onStopped = { onWorkspaceStopped(forwarder, thinClient) }
        )
        sessionWatch.start(sessionWatchScope)

        thinClient.lifetime.onTermination {
            sessionWatch.stop()
            sessionWatchScope.cancel()
        }
    }

    /**
     * The workspace that backs the session stopped (Dashboard, Gateway, deletion);
     * [watchSession]'s stop policy calls this.
     *
     * A stopped workspace may be started again (CRW-13910): don't stop recovery.
     * - cut the forwarding connection
     * - focus the guest window to surface connection status
     */
    @Suppress("UnstableApiUsage")
    internal fun onWorkspaceStopped(forwarder: PortForwardSession, thinClient: ThinClientHandle) {
        forwarder.dropClientConnections()
        // occluded Guest defers repaint; focus to surface updated status
        runCatching { thinClient.focusClientWindow() }
    }

    /** The forward ended: the Guest lost its connection. Starts the session's recovery episode. */
    @Suppress("UnstableApiUsage")
    internal fun onRemoteEnd(client: ThinClientHandle?) {
        recovery.startRecovery(client)
    }

    /**
     * Installs the recovery watcher of this session; [ThinClientRecovery.prepareRecovery] wires the
     * production one. Internal so a test can emulate a recovery in progress without a cluster.
     */
    internal fun installRecoveryWatcher(
        watcher: ThinClientRecoveryWatcher?,
        scope: CoroutineScope? = null
    ) {
        recovery.installRecoveryWatcher(watcher, scope)
    }

    /**
     * Returns the handler for the restart annotation. [DevWorkspaceRestart.execute] only submits a
     * background task, so it runs inline on the watch coroutine (already off the EDT): the watch
     * then sees a failure and retries on the next event.
     */
    @Suppress("UnstableApiUsage")
    private fun onRestartAnnotated(
        thinClient: ThinClientHandle,
        workspace: DevWorkspace
    ): () -> Unit =
        { DevWorkspaceRestart(devSpacesContext).execute(thinClient, workspace) }

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
    private fun tearDownConnection(attempt: ConnectAttempt) {
        if (!attempt.tearDownStarted.compareAndSet(false, true)) {
            return
        }
        val workspace = attempt.workspace
        // Clear tracking + refresh UI before any remote wait so Connect is not stuck
        // behind waitServerTerminated (up to 10s) when the wizard stays open in IDEA.
        devSpacesContext.removeWorkspace(workspace)
        runCatching { attempt.onConnectionEnded() }

        CoroutineScope(Dispatchers.IO).launch {
            // §7.9: the state is read *before* stopping, so the teardown below still knows that a
            // recovery was running and withholds only the remote stop()
            val recoveryWasActive = recovery.stopRecovery()
            runCatching { attempt.thinClient?.close() }
            val workspacePatch = DevWorkspacePatch(
                workspace.namespace,
                workspace.name,
                devSpacesContext.client
            ) {
                DevWorkspaces(devSpacesContext.client)
                    .get(workspace.namespace, workspace.name)
            }
            try {
                val restartInProgress = workspacePatch.hasRestartAnnotation()
                if (!restartInProgress
                    && !recoveryWasActive
                    && true == attempt.remoteIdeServer?.waitServerTerminated()) {
                    // User closed the IDE and asked to stop the workspace.
                    DevWorkspaces(devSpacesContext.client)
                        .stop(workspace.namespace, workspace.name)
                        .also { attempt.onDevWorkspaceStopped() }
                }
            } finally {
                runCatching { attempt.forwarder?.close() }
                    .onFailure { e -> thisLogger().debug("Failed to close port forwarder", e) }
            }
        }
    }

    /**
     * Handles the case where the remote IDE server is not ready after starting the workspace.
     * Shows a dialog and either restarts the workspace (return true) or indicates cancellation (return false).
     */
    private fun handleServerNotReady(
        workspace: DevWorkspace,
        checkCancelled: (() -> Unit)?,
        modalityState: ModalityState?
    ): Boolean {
        val restartWorkspace = Dialogs.ideNotResponding(modalityState)
        if (restartWorkspace) {
            DevWorkspaces(devSpacesContext.client).stopAndWait(
                workspace.namespace,
                workspace.name,
                checkCancelled = checkCancelled
            )
        }
        return restartWorkspace
    }

    private suspend fun waitUntilServerReady(
        workspace: DevWorkspace,
        checkCancelled: (() -> Unit)?,
        onProgress: ((ProgressCountdown.ProgressEvent) -> Unit)?,
        modalityState: ModalityState?
    ): RemoteIDEServer {
        while (true) {
            checkCancelled?.invoke()
            onProgress?.invoke(
                ProgressCountdown.ProgressEvent(
                    message = "",
                    countdownSeconds = DevWorkspaces.RUNNING_TIMEOUT
                )
            )
            DevWorkspaces(devSpacesContext.client)
                .startAndWait(workspace.namespace, workspace.name, checkCancelled = checkCancelled)

            checkCancelled?.invoke()
            onProgress?.invoke(
                ProgressCountdown.ProgressEvent(
                    title = WAITING_FOR_WORKSPACE_TITLE,
                    countdownSeconds = RemoteIDEServer.READY_TIMEOUT
                )
            )
            val remoteIdeServer = RemoteIDEServer(devSpacesContext, workspace)
            val status = awaitServerStatus(remoteIdeServer, checkCancelled)

            checkCancelled?.invoke()
            if (status.isReady) return remoteIdeServer
            if (!handleServerNotReady(workspace, checkCancelled, modalityState)) {
                throw CancellationException("User cancelled the operation")
            }
        }
    }

    /**
     * The status after waiting for readiness. A container that vanished is terminal; any other
     * failure reads as "not ready" so the caller can offer a restart.
     */
    private suspend fun awaitServerStatus(
        remoteIdeServer: RemoteIDEServer,
        checkCancelled: (() -> Unit)?
    ): RemoteIDEServerStatus =
        runCatching {
            remoteIdeServer.apply { waitServerReady(checkCancelled) }.getStatus(checkCancelled)
        }.getOrElse { e ->
            if (e.isCancellationException()) throw e
            // no idea-server container, don't offer "restart pod" (CRW-11897).
            if (e.isServerContainerNotFound()) throw e
            RemoteIDEServerStatus.empty()
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
        attempt: ConnectAttempt,
        onConnected: () -> Unit,
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
        attempt.thinClient = thinClient

        fun notifyThinClientClosed() {
            onThinClientClosed(attempt)
        }
        thinClient.clientClosed.advise(thinClient.lifetime) { notifyThinClientClosed() }
        thinClient.clientFailedToOpenProject.advise(thinClient.lifetime) { notifyThinClientClosed() }

        return thinClient
    }

    /**
     * Waits for the JetBrains Thin Client to become available.
     *
     * The first-time JetBrains Client download may take several minutes. This phase waits
     * until [ThinClientHandle.clientPresent] is true, or until the wait is ended via
     * [ConnectAttempt.connectWaitEnded] (clientClosed / clientFailedToOpenProject). There is no
     * wall-clock timeout on this wait.
     *
     * A permanent failure is reported as [ThinClientLaunchException] — the client was
     * started but closed or failed to open the project, e.g. because the platform's own
     * download or extraction failed (details are in the IDE log and the platform's error
     * notification); the workspace IDE itself is not at fault.
     */
    @Suppress("UnstableApiUsage")
    internal suspend fun waitForConnect(
        attempt: ConnectAttempt,
        checkCancelled: (() -> Unit)?
    ) {
        val client = checkNotNull(attempt.thinClient) {
            "Cannot wait for a client that was not started"
        }
        while (!client.clientPresent && !attempt.connectWaitEnded.get()) {
            checkCancelled?.invoke()
            @Suppress("ConvertLongToDuration")
            delay(CONNECT_POLL)
        }

        if (!client.clientPresent || attempt.connectWaitEnded.get()) {
            throw ThinClientLaunchException(
                "Could not start the JetBrains Client. See the IDE log for details."
            )
        }
    }
}
