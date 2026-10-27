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
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceSessionWatch
import com.redhat.devtools.gateway.devworkspace.DevWorkspaces
import com.redhat.devtools.gateway.download.DevSpacesClientConnectProgress
import com.redhat.devtools.gateway.download.DevSpacesClientMirror
import com.redhat.devtools.gateway.openshift.PodPortForwarder
import com.redhat.devtools.gateway.openshift.PortForwardSession
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.server.RemoteIDEServerStatus
import com.redhat.devtools.gateway.util.ProgressCountdown
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.util.isServerContainerNotFound
import com.redhat.devtools.gateway.view.ui.Dialogs
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin-client connection lifecycle.
 *
 * Thin-client close or failed-to-open sets [ThinClientSessionContext.connectFailed] (ending the
 * connect wait); [tearDownConnection] runs only if the connection is already live
 * ([ThinClientSessionContext.connectionLive]). Failures during connect are cleaned up by
 * [connect]'s catch path.
 *
 * A pod roll mid-session is recovered in place: the port forwarder re-resolves the new pod per
 * connection, and [ThinClientReconnect] heals or replaces the thin client (CRW-13910). A close
 * of the client that the reconnect did not issue ends the session — see
 * [onThinClientClosed].
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
class DevSpacesConnection(
    private val devSpacesContext: DevSpacesContext,
    /**
     * Runs the Guest focus request on the UI thread: the port-forward and watch callbacks run on
     * background threads, while focusing the thin client is EDT-bound. Overridable in tests, which
     * run without an application to dispatch to.
     */
    private val runOnEdt: (() -> Unit) -> Unit = { action ->
        ApplicationManager.getApplication().invokeLater(action, ModalityState.nonModal())
    }
) {

    companion object {
        private const val CONNECT_POLL: Long = 200 // millis
        private const val FOCUS_REQUEST_INTERVAL_MS: Long = 5_000
    }

    /** Ensures [tearDownConnection] runs at most once for this connect attempt. */
    private val tearDownStarted = AtomicBoolean(false)

    /** When the Guest window was last asked to come to the front; guarded by [focusClientWindow]. */
    private var lastFocusRequestMillis = 0L

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
        // a reconnect must be able to focus at once, not wait out the previous session's throttle
        lastFocusRequestMillis = 0L
        devSpacesContext.addWorkspace(workspace)

        var remoteIdeServer: RemoteIDEServer? = null
        var forwarder: PortForwardSession? = null
        var client: ThinClientHandle? = null
        var clientMirror: AutoCloseable? = null
        var sessionCtx: ThinClientSessionContext? = null
        val progress = DevSpacesClientConnectProgress(onProgress)

        return try {
            remoteIdeServer = waitUntilServerReady(checkCancelled, onProgress, modalityState)

            checkCancelled?.invoke()
            val joinLink = remoteIdeServer.getStatus(checkCancelled).joinLink
                ?: throw ThinClientNotReadyException(
                    "Could not connect, workspace IDE is not ready. No join link present.")

            checkCancelled?.invoke()
            progress.waitingForClientStart()

            val localPort = findFreePort()
            val session = ThinClientSessionContext(
                localPort = localPort,
                remoteIdeServer = remoteIdeServer,
                onConnected = onConnected,
                onDisconnected = onConnectionEnded,
                onDevWorkspaceStopped = onDevWorkspaceStopped,
            )
            sessionCtx = session

            val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            session.recoveryScope = recoveryScope

            val forwardRecovery = ForwardRecoveryProgress(
                scope = recoveryScope,
                sessionCtx = session,
                isWorkspaceRestartInProgress = ::isWorkspaceRestartInProgress,
                onCanceled = {
                    session.cancelReconnect()
                    tearDownConnection(
                        client,
                        workspace,
                        onConnectionEnded,
                        onDevWorkspaceStopped,
                        remoteIdeServer,
                        session.forwarder,
                        session.recoveryScope,
                    )
                },
            )

            val tracker = WorkspacePodTracker(remoteIdeServer, ::isWorkspaceRestartInProgress)
            tracker.seed(remoteIdeServer.pod)

            val portForwardResolver = PortForwardPodResolver(tracker, session, forwardRecovery)

            val forwardSession = setupPortForwarding(
                localPort = localPort,
                resolvePod = portForwardResolver::resolve,
                // the forwarder outlives the client handle creation: resolve the handle lazily
                onRemoteEnd = { session.thinClient?.let { focusClientWindow(it) } },
            )
            forwarder = forwardSession
            session.forwarder = forwardSession

            setupThinClientReconnect(
                sessionCtx = session,
                forwardRecovery = forwardRecovery,
                tracker = tracker,
                reconnect = ThinClientReconnect(
                    remoteIdeServer = remoteIdeServer,
                    sessionCtx = session,
                    startThinClient = { link, reconnectCtx ->
                        startThinClientOnReconnect(
                            joinLink = link,
                            sessionCtx = reconnectCtx,
                            workspace = workspace,
                            checkCancelled = { reconnectCtx.throwIfReconnectCanceled() },
                        )
                    },
                    onClientHandleReplaced = { newClient ->
                        client = newClient
                        session.thinClient = newClient
                        // the replacement client needs its own watch: the watch of the replaced
                        // client stopped with its lifetime
                        watchSession(workspace, newClient, forwardSession, handleRestarts = registerRestartWatcher)
                    },
                    onClientClosed = { closedClient, reconnectCtx ->
                        onReconnectClientClosed(closedClient, reconnectCtx, workspace)
                    },
                ),
            )

            val localJoinLink = ThinClientJoinLink.withLocalPort(joinLink, localPort)

            checkCancelled?.invoke()
            clientMirror = mirrorClient(URI(localJoinLink), progress, checkCancelled)
            val thinClient = startThinClient(joinLink, session, workspace)
            client = thinClient

            progress.startingClient()
            waitForConnect(thinClient, session.connectFailed, checkCancelled)

            session.connectionLive.set(true)
            onConnected()
            watchSession(workspace, thinClient, forwardSession, handleRestarts = registerRestartWatcher)
            thinClient
        } catch (e: Exception) {
            sessionCtx?.cancelReconnect()
            if (e !is ThinClientLaunchException || sessionCtx?.connectionLive?.get() == true) {
                runCatching { client?.close() }
            }
            tearDownConnection(
                client,
                workspace,
                onConnectionEnded,
                onDevWorkspaceStopped,
                remoteIdeServer,
                forwarder,
                sessionCtx?.recoveryScope,
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
     *
     * Closes owned by the pod-roll reconnect itself (see
     * [ThinClientSessionContext.consumeReconnectOwnedClose]) are ignored — including stale
     * or repeated events that arrive after a successful reconnect. Any other close while a
     * reconnect is in flight means the client process ended (user close or crash): the
     * reconnect is canceled so it cannot respawn a client for a dead session, and the
     * session is torn down.
     */
    @Suppress("UnstableApiUsage")
    internal fun onThinClientClosed(
        thinClient: ThinClientHandle,
        workspace: DevWorkspace,
        sessionCtx: ThinClientSessionContext,
    ) {
        if (sessionCtx.consumeReconnectOwnedClose(thinClient)) {
            thisLogger().debug("Thin client closed by the reconnect itself; ignoring close event")
            return
        }
        sessionCtx.connectFailed.set(true)
        if (sessionCtx.reconnecting.get()) {
            thisLogger().info("Thin client closed while reconnecting; aborting pod-roll reconnect")
            sessionCtx.cancelReconnect()
        }
        if (sessionCtx.connectionLive.get()) {
            tearDownConnection(
                thinClient,
                workspace,
                sessionCtx.onDisconnected,
                sessionCtx.onDevWorkspaceStopped,
                sessionCtx.remoteIdeServer,
                sessionCtx.forwarder,
                sessionCtx.recoveryScope,
            )
        }
    }

    /**
     * Tears the session down when reconnect permanently fails or is cancelled. [ThinClientReconnect]
     * clears [ThinClientSessionContext.reconnecting] before invoking this callback.
     */
    @Suppress("UnstableApiUsage")
    private fun onReconnectClientClosed(
        client: ThinClientHandle?,
        sessionCtx: ThinClientSessionContext,
        workspace: DevWorkspace,
    ) {
        if (!sessionCtx.connectionLive.get()) {
            return
        }
        tearDownConnection(
            client,
            workspace,
            sessionCtx.onDisconnected,
            sessionCtx.onDevWorkspaceStopped,
            sessionCtx.remoteIdeServer,
            sessionCtx.forwarder,
            sessionCtx.recoveryScope,
        )
    }

    /**
     * Wires [WorkspacePodTracker.onPodRoll] to [ThinClientReconnect.execute] and the reconnect's
     * cancellation to [ThinClientSessionContext.cancelReconnect].
     *
     * The tracker decides whether a roll is a recoverable pod roll or an annotated restart that
     * [DevWorkspaceRestart] owns; this method only routes a delegated roll to reconnect.
     */
    private fun setupThinClientReconnect(
        sessionCtx: ThinClientSessionContext,
        forwardRecovery: ForwardRecoveryProgress,
        tracker: WorkspacePodTracker,
        reconnect: ThinClientReconnect,
    ) {
        sessionCtx.cancelReconnect = reconnect::cancel
        tracker.onPodRoll = { pod ->
            forwardRecovery.dismiss()
            reconnect.execute(pod)
        }
    }

    /**
     * Returns true when the DevWorkspace carries [DevWorkspace.RESTART_KEY].
     *
     * Used by [WorkspacePodTracker] to suppress pod-roll reconnect while
     * [DevWorkspaceRestart] owns the session recovery.
     */
    private fun isWorkspaceRestartInProgress(): Boolean =
        runCatching {
            val workspace = devSpacesContext.devWorkspace
            DevWorkspaces(devSpacesContext.client).isRestarting(
                workspace.namespace,
                workspace.name,
            )
        }.getOrDefault(false)

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
            onRestartAnnotated = if (handleRestarts) onRestartAnnotated(thinClient) else null,
            onStopped = {
                // Closing the forwarder is what makes the Guest read EOF. Its status widget then
                // reports the loss at once, instead of waiting for the pod to terminate and for
                // the forwarded stream to end. The listener must go too: the stopped workspace
                // has no running pod, so a re-dial would only wait for one that never returns.
                forwarder.close()
                // An occluded JetBrains Client defers the repaint of its status widget, so the
                // loss would only become visible once the user focuses it. Asking for the window
                // focus makes the Guest surface the updated status right away.
                focusClientWindow(thinClient)
            }
        )
        sessionWatch.start(sessionWatchScope)

        thinClient.lifetime.onTermination {
            sessionWatch.stop()
            sessionWatchScope.cancel()
        }
    }

    /**
     * Brings the JetBrains Client window to the front so it repaints its connection status.
     *
     * Throttled: a forward that keeps ending and re-establishing (pod flapping) must not pull the
     * window to the front on every cut.
     */
    @Synchronized
    @Suppress("UnstableApiUsage")
    internal fun focusClientWindow(thinClient: ThinClientHandle) {
        val now = System.currentTimeMillis()
        if (now - lastFocusRequestMillis < FOCUS_REQUEST_INTERVAL_MS) return
        lastFocusRequestMillis = now
        // the focus is EDT-bound while this runs on the port-forward / watch threads
        runOnEdt {
            runCatching { thinClient.focusClientWindow() }
                .onFailure { e -> thisLogger().debug("Could not focus the JetBrains Client window", e) }
        }
    }

    /**
     * Returns the handler for the restart annotation. [DevWorkspaceRestart.execute] only submits a
     * background task, so it runs directly: the watch invokes the handler off its consumer thread.
     * Staying synchronous there lets the watch see a failure and retry on the next event.
     */
    @Suppress("UnstableApiUsage")
    private fun onRestartAnnotated(thinClient: ThinClientHandle): () -> Unit =
        { DevWorkspaceRestart(devSpacesContext).execute(thinClient) }

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
        forwarder: PortForwardSession?,
        forwardRecoveryScope: CoroutineScope? = null,
    ) {
        if (!tearDownStarted.compareAndSet(false, true)) {
            return
        }
        forwardRecoveryScope?.cancel()
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
                countdownSeconds = RemoteIDEServer.READY_TIMEOUT)
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

    /**
     * Starts the local port-forward once for the whole session. [resolvePod] runs per accepted
     * connection, so a recreated pod is picked up without replacing the forwarder.
     *
     * @param onRemoteEnd brings the Guest to the front when the pod side ends an established
     * forward, so an occluded window repaints the lost connection.
     */
    private fun setupPortForwarding(
        localPort: Int,
        resolvePod: suspend () -> V1Pod,
        onRemoteEnd: () -> Unit,
    ): PortForwardSession {
        val forwarder = PodPortForwarder(devSpacesContext.client)
        val session = forwarder.forward(
            resolvePod = resolvePod,
            localPort = localPort,
            remotePort = ThinClientJoinLink.REMOTE_IDE_PORT,
            onRemoteEnd = onRemoteEnd,
        )
        forwarder.waitForForwardReady(localPort)
        return session
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

    /**
     * Starts a thin client for [sessionCtx], rewriting the remote `:5990` in [joinLink] to the
     * session's local port and attaching listeners that respect pod-roll reconnect.
     *
     * Used for the initial connection and for the reconnect fallback that replaces a stale
     * client. The initial connection fires [ThinClientSessionContext.onConnected] once the
     * client started (default); the reconnect fallback overrides [onClientStarted] with a
     * no-op because the session is already connected.
     */
    @Suppress("UnstableApiUsage")
    internal fun startThinClient(
        joinLink: String,
        sessionCtx: ThinClientSessionContext,
        workspace: DevWorkspace,
        onClientStarted: (() -> Unit)? = null,
    ): ThinClientHandle {
        val localJoinLink = URI(
            ThinClientJoinLink.withLocalPort(joinLink, sessionCtx.localPort)
        )
        val thinClient = LinkedClientManager
            .getInstance()
            .startNewClient(
                Lifetime.Eternal,
                localJoinLink,
                "",
                onClientStarted ?: sessionCtx.onConnected,
                false
            )
        sessionCtx.thinClient = thinClient
        attachClientListeners(thinClient, sessionCtx, workspace)
        return thinClient
    }

    /**
     * Starts the replacement thin client for a pod-roll reconnect (CRW-13910).
     *
     * Mirrors the JetBrains Client for the join link first (same as the initial connect): the
     * session's connect-time mirror activation is already closed, so without this the platform
     * downloader hits the network again even when the client is already on disk. The mirror
     * stays active until [waitForConnect] finishes (client present or permanent failure).
     *
     * The wizard already handled [ThinClientSessionContext.onConnected] for this session, so
     * the replacement starts with a no-op callback instead of re-firing wizard connect logic.
     */
    @Suppress("UnstableApiUsage")
    internal suspend fun startThinClientOnReconnect(
        joinLink: String,
        sessionCtx: ThinClientSessionContext,
        workspace: DevWorkspace,
        checkCancelled: (() -> Unit)? = null,
    ): ThinClientHandle {
        val localJoinLink = URI(ThinClientJoinLink.withLocalPort(joinLink, sessionCtx.localPort))
        return mirrorClient(localJoinLink, DevSpacesClientConnectProgress(), checkCancelled).use {
            // a prior close during replace must not make waitForConnect think launch already failed
            sessionCtx.connectFailed.set(false)
            val handle = startThinClient(joinLink, sessionCtx, workspace, onClientStarted = {})
            waitForConnect(handle, sessionCtx.connectFailed, checkCancelled)
            handle
        }
    }

    @Suppress("UnstableApiUsage")
    private fun attachClientListeners(
        thinClient: ThinClientHandle,
        sessionCtx: ThinClientSessionContext,
        workspace: DevWorkspace,
    ) {
        fun notifyThinClientClosed() {
            onThinClientClosed(thinClient, workspace, sessionCtx)
        }
        thinClient.clientClosed.advise(thinClient.lifetime) { notifyThinClientClosed() }
        thinClient.clientFailedToOpenProject.advise(thinClient.lifetime) { notifyThinClientClosed() }
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
