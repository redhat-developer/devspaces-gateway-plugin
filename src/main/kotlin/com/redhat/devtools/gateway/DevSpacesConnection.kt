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
import com.redhat.devtools.gateway.openshift.PodPortForwarder
import com.redhat.devtools.gateway.openshift.PortForwardSession
import com.redhat.devtools.gateway.openshift.PortForwarder
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.server.RemoteIDEServerStatus
import com.redhat.devtools.gateway.util.ProgressCountdown
import com.redhat.devtools.gateway.util.callerFrames
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.util.isServerContainerNotFound
import com.redhat.devtools.gateway.view.ui.Dialogs
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.*
import java.io.IOException
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
 *
 * A live connection also watches its DevWorkspace: a restart annotation from the remote IDE
 * restarts it, and a stop (Dashboard or Gateway) closes the port forward at once so the Guest
 * reports the loss without waiting for the pod to terminate.
 */
@Suppress("TooManyFunctions") // the connection lifecycle is one cohesive operation
class DevSpacesConnection internal constructor(
    private val devSpacesContext: DevSpacesContext,
    private val clientWindowFocus: ClientWindowFocus,
    private val portForwarder: PortForwarder
) {

    constructor(
        devSpacesContext: DevSpacesContext,
        clientWindowFocus: ClientWindowFocus = ClientWindowFocus()
    ) : this(devSpacesContext, clientWindowFocus, PodPortForwarder(devSpacesContext.client))

    companion object {
        private const val CONNECT_POLL: Long = 200 // millis
    }

    /** Ensures [tearDownConnection] runs at most once for this connect attempt. */
    private val tearDownStarted = AtomicBoolean(false)

    /**
     * Watches the Guest's re-dial while the connection is lost. Set once the session is live and
     * cleared when recovery ends; [tearDownConnection] reads its state to withhold the workspace
     * auto-stop (§7.9) and [startRecovery] starts it from the forwarder's remote-end signal.
     */
    @Volatile
    private var recoveryWatcher: ThinClientRecoveryWatcher? = null

    /** Owns the recovery loop, so cancelling it ends recovery with the client session. */
    @Volatile
    private var recoveryScope: CoroutineScope? = null

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
        tearDownStarted.set(false)
        stopRecovery()
        clientWindowFocus.resetThrottle()
        devSpacesContext.addWorkspace(workspace)

        var remoteIdeServer: RemoteIDEServer? = null
        var forwarder: PortForwardSession? = null
        var client: ThinClientHandle? = null
        var clientMirror: AutoCloseable? = null
        val connectionLive = AtomicBoolean(false)
        val progress = DevSpacesClientConnectProgress(onProgress)

        return try {
            remoteIdeServer = waitUntilServerReady(workspace, checkCancelled, onProgress, modalityState)

            checkCancelled?.invoke()
            val joinLink = remoteIdeServer.getStatus(checkCancelled).joinLink
                ?: throw ThinClientNotReadyException(
                    "Could not connect, workspace IDE is not ready. No join link present.")

            checkCancelled?.invoke()
            progress.waitingForClientStart()

            // the forwarder outlives the client handle creation: resolve the handle lazily.
            // The resolver lets the forward follow a replacement pod: while the DevWorkspace stays
            // Running, the workspace - not the pod pinned here - is what keeps serving.
            val portForwardSession = portForwarder
                .forward(
                    remoteIdeServer.pod,
                    ThinClientJoinLink.REMOTE_IDE_PORT,
                    { findRunningPod(workspace) },
                ) {
                    onRemoteEnd(client)
                }

            forwarder = portForwardSession

            val localJoinLink = ThinClientJoinLink.toLocal(URI(joinLink), portForwardSession.localPort)
            val connectFailed = AtomicBoolean(false)

            checkCancelled?.invoke()
            clientMirror = mirrorClient(localJoinLink, progress, checkCancelled)
            client = startClient(
                localJoinLink, workspace, onConnected, onConnectionEnded, onDevWorkspaceStopped,
                remoteIdeServer, forwarder, connectFailed, connectionLive
            )

            progress.startingClient()
            waitForConnect(client, connectFailed, checkCancelled)

            connectionLive.set(true)
            portForwardSession.markEstablished()
            onConnected()
            watchSession(workspace, client, portForwardSession, handleRestarts = registerRestartWatcher)
            // the pod the local join link was built against: a different pod serves the same link
            // with a new idea-server session token, so recovery must treat the link as stale
            prepareRecovery(workspace, client, portForwardSession, localJoinLink, remoteIdeServer.pod)
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
        forwarder: PortForwardSession?,
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

    /**
     * Watches the workspace that backs this session for the user actions that end it: a restart
     * annotation set by the remote IDE and a stop requested from Dashboard or Gateway. One watch
     * stream serves both; see [DevWorkspaceSessionWatch].
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
            onStopped = {
                // the workspace is going down: nothing to recover into
                stopRecovery()
                // close sockets -> Guest EOF, immediate status loss
                // close listener -> pod pinned, re-dial would hit stopped pod
                forwarder.close()
                // occluded Guest defers repaint; focus to surface updated status
                clientWindowFocus.requestFocus(thinClient)
            }
        )
        sessionWatch.start(sessionWatchScope)

        thinClient.lifetime.onTermination {
            sessionWatch.stop()
            sessionWatchScope.cancel()
            stopRecovery()
        }
    }

    /**
     * Builds the recovery watcher of this session. Nothing starts yet: the Guest may be perfectly
     * fine. [startRecovery] starts it on the forward's remote-end signal; the client's lifetime and
     * the teardown stop it.
     *
     * Recovery observes the existing Guest only: it resolves the current workspace pod itself and
     * prods that Guest to re-dial. It never starts a new client and never closes one.
     *
     * @param sessionPod the pod [localJoinLink] was built against; when a different pod serves the
     *   workspace, the link's session token is unknown to it and recovery refreshes the link.
     */
    private fun prepareRecovery(
        workspace: DevWorkspace,
        thinClient: ThinClientHandle,
        session: PortForwardSession,
        localJoinLink: URI,
        sessionPod: V1Pod
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        recoveryScope = scope
        recoveryWatcher = ThinClientRecoveryWatcher(
            ThinClientRecoveryWatcher.Collaborators(
                resolvePod = { findRunningPod(workspace) },
                probe = { pod -> portForwarder.probe(pod, ThinClientJoinLink.REMOTE_IDE_PORT) },
                cachedLink = localJoinLink,
                sessionPod = sessionPod,
                refreshLink = refreshJoinLink(workspace, session.localPort),
                prod = prodJoinLink(thinClient),
                session = session,
                isClientPresent = { thinClient.clientPresent },
                onRecovered = { reportRecovered(thinClient) },
                onRecoveryFailed = { reportRecoveryFailed(thinClient, workspace) }
            )
        )
        thinClient.lifetime.onTermination {
            stopRecovery()
            scope.cancel()
        }
    }

    /**
     * The forward ended: the Guest lost its connection. Surface the loss and prod the Guest to
     * re-dial. Starting is idempotent, so a session that keeps ending does not spawn a second loop.
     */
    private fun onRemoteEnd(client: ThinClientHandle?) {
        client?.let(clientWindowFocus::requestFocus)
        startRecovery()
    }

    private fun startRecovery() {
        val watcher = recoveryWatcher
        val scope = recoveryScope
        // teardown already running: a connection that ends now must not start a recovery
        if (!tearDownStarted.get() && watcher != null && scope != null) {
            watcher.start(scope)
        }
    }

    /**
     * Stops recovery and reports whether it was running.
     *
     * The running state is read *before* the watcher is stopped, so stopping does not erase it; the
     * return value carries it to the caller. Teardown uses it to withhold the workspace auto-stop
     * (§7.9): a Guest closed during recovery must not stop a workspace that may come back once the
     * outage clears. Only the remote `stop()` is withheld — the rest of the teardown is unchanged.
     */
    private fun stopRecovery(): Boolean {
        val watcher = recoveryWatcher
        val wasActive = watcher?.isActive == true
        if (watcher != null) {
            thisLogger().info(
                "Stopping connection recovery (wasActive=$wasActive); " +
                    "caller=${callerFrames(javaClass.name)}"
            )
        }
        watcher?.stop()
        recoveryWatcher = null
        recoveryScope?.cancel()
        recoveryScope = null
        return wasActive
    }

    /**
     * Installs the recovery watcher of this session; [prepareRecovery] wires the production one.
     * Internal so a test can emulate a recovery in progress without a cluster.
     */
    internal fun installRecoveryWatcher(watcher: ThinClientRecoveryWatcher?) {
        recoveryWatcher = watcher
    }

    /**
     * The pod that serves [workspace] right now. Runs on the watcher's IO coroutine; a failure is
     * retried on its next poll.
     */
    private fun findRunningPod(workspace: DevWorkspace): V1Pod? =
        DevWorkspacePods(devSpacesContext.client).findFirstRunning(
            workspace.namespace,
            "${DevWorkspacePods.WORKSPACE_LABEL_KEY}=${workspace.name}"
        )

    /**
     * Builds a fresh local join link for the *current* pod, or `null` while none can be built yet.
     *
     * Constructing [RemoteIDEServer] resolves the pod and its container atomically; its
     * [IOException] means "no running, ready pod yet", which is not a recovery failure — the watcher
     * keeps polling. [RemoteIDEServer.getStatus] execs the IDE server status once, bounded by
     * [RemoteIDEServer.STATUS_EXEC_TIMEOUT], and runs off the EDT.
     *
     * A server that answers but reports no usable link is logged at info: recovery waits instead of
     * prodding while that is the case, so "why is it not prodding?" must be answerable from the log.
     */
    @Suppress("TooGenericExceptionCaught") // a malformed link or a failed exec only means "poll again"
    private fun refreshJoinLink(workspace: DevWorkspace, localPort: Int): suspend () -> URI? = {
        try {
            val status = RemoteIDEServer(devSpacesContext, workspace).getStatus()
            if (status.isReady && status.joinLink != null) {
                ThinClientJoinLink.toLocal(URI(status.joinLink), localPort)
            } else {
                thisLogger().info(
                    "Workspace '${workspace.name}' reports no join link yet " +
                        "(ready=${status.isReady}); recovery waits for a fresh link."
                )
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            thisLogger().debug("No running, ready pod to build a join link from; retrying.", e)
            null
        } catch (e: Exception) {
            // a malformed link or a failed exec must not end recovery: the next poll retries
            if (e.isCancellationException()) throw e
            thisLogger().debug("Could not build a fresh join link; retrying.", e)
            null
        }
    }

    /**
     * The transparent lever (verified in the Guest): `updateJoinLink(forceReconnect = true)` drops
     * the RD link and re-dials the given one, keeping the window open. A throwing handle is caught
     * by the watcher, which then checks whether the client is still present.
     */
    @Suppress("UnstableApiUsage")
    private fun prodJoinLink(thinClient: ThinClientHandle): suspend (URI) -> Unit = { link ->
        thinClient.updateJoinLink(link, true)
    }

    private fun reportRecovered(thinClient: ThinClientHandle) {
        thisLogger().info(
            "Recovered the connection to the workspace; the JetBrains Client keeps its window."
        )
        // the Guest repaints its connection status when its window is brought to the front
        clientWindowFocus.requestFocus(thinClient)
    }

    /**
     * Recovery is definitively over. Reported only if the Guest still exists: a Guest that is gone
     * was closed by the user or by the platform's own websocket timeout, so there is nobody to tell.
     * Nothing is relaunched and no client is closed; the workspace stop decision is no longer ours.
     */
    private fun reportRecoveryFailed(thinClient: ThinClientHandle, workspace: DevWorkspace) {
        val workspacePath = "${workspace.namespace}/${workspace.name}"
        if (!thinClient.clientPresent) {
            thisLogger().warn(
                "Could not restore the connection to workspace $workspacePath; " +
                    "the JetBrains Client is gone, not showing a dialog."
            )
            return
        }
        thisLogger().warn("Could not restore the connection to workspace $workspacePath.")
        Dialogs.error(
            "The connection to workspace '${workspace.name}' could not be restored. " +
                "Close the client and connect again.",
            "Connection Error"
        )
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
    private fun tearDownConnection(
        client: ThinClientHandle? = null,
        workspace: DevWorkspace,
        onConnectionEnded: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        remoteIdeServer: RemoteIDEServer?,
        forwarder: PortForwardSession?
    ) {
        if (!tearDownStarted.compareAndSet(false, true)) {
            return
        }
        // Clear tracking + refresh UI before any remote wait so Connect is not stuck
        // behind waitServerTerminated (up to 10s) when the wizard stays open in IDEA.
        devSpacesContext.removeWorkspace(workspace)
        runCatching { onConnectionEnded() }

        CoroutineScope(Dispatchers.IO).launch {
            // §7.9: the state is read *before* stopping, so the teardown below still knows that a
            // recovery was running and withholds only the remote stop()
            val recoveryWasActive = stopRecovery()
            runCatching { client?.close() }
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
                    && true == remoteIdeServer?.waitServerTerminated()) {
                    // User closed the IDE and asked to stop the workspace.
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
        var remoteIdeServerStatus: RemoteIDEServerStatus = RemoteIDEServerStatus.empty()
        var remoteIdeServer: RemoteIDEServer? = null

        while (!remoteIdeServerStatus.isReady) {
            checkCancelled?.invoke()
            onProgress?.invoke(ProgressCountdown.ProgressEvent(
                message = "",
                countdownSeconds = DevWorkspaces.RUNNING_TIMEOUT))

            DevWorkspaces(devSpacesContext.client)
                .startAndWait(
                    workspace.namespace,
                    workspace.name,
                    checkCancelled = checkCancelled)

            checkCancelled?.invoke()
            onProgress?.invoke(ProgressCountdown.ProgressEvent(
                title = "Waiting for the workspace to get ready...",
                countdownSeconds = RemoteIDEServer.READY_TIMEOUT)
            )

            remoteIdeServer = RemoteIDEServer(devSpacesContext, workspace)
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
                if (handleServerNotReady(workspace, checkCancelled, modalityState)) {
                    continue
                } else {
                    throw CancellationException("User cancelled the operation")
                }
            }
        }
        return remoteIdeServer!!
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
        forwarder: PortForwardSession?,
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
