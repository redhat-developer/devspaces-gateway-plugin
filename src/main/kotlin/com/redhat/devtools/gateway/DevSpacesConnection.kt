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
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.devworkspace.DevWorkspacePatch
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceRestart
import com.redhat.devtools.gateway.devworkspace.DevWorkspaces
import com.redhat.devtools.gateway.devworkspace.RestartDevWorkspaceAnnotationWatch
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import com.redhat.devtools.gateway.openshift.PodForwardResolution
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.server.RemoteIDEServerStatus
import com.redhat.devtools.gateway.util.ProgressCountdown
import com.redhat.devtools.gateway.util.closeAllProjects
import com.redhat.devtools.gateway.util.findFreePort
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.view.ui.Dialogs
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Connects Gateway to a workspace IDE via port-forward and thin client.
 *
 * ## Session recovery routing
 *
 * Two independent paths recover from pod or workspace changes. They must not run at the same time.
 *
 * **Unplanned pod roll** (pod UID changes, no restart annotation):
 * [WorkspacePodTracker] detects the roll and invokes [ThinClientReconnect] ("Reconnecting to workspace").
 * [PortForwardPodResolver] waits for a running pod when needed, then re-establishes port-forward
 * in parallel with session reconnect. The existing local port and forwarder are reused.
 *
 * **User-initiated restart** (Remote IDE sets [DevWorkspacePatch.RESTART_KEY] on the DevWorkspace):
 * [RestartDevWorkspaceAnnotationWatch] triggers [DevWorkspaceRestart] ("Restart workspace"), which
 * stops and starts the DevWorkspace and opens a new connection. While the annotation is present,
 * [WorkspacePodTracker] skips pod-roll reconnect so the two handlers do not compete.
 *
 * Wiring: [setupThinClientReconnect] registers the tracker callback; [watchRestartAnnotation] starts
 * the annotation watch. [isWorkspaceRestartInProgress] is the guard passed into the tracker.
 */
class DevSpacesConnection(private val devSpacesContext: DevSpacesContext) {

    companion object {
        val THIN_CLIENT_TIMEOUT: kotlin.time.Duration = 60.seconds
        val THIN_CLIENT_POLL_DELAY: kotlin.time.Duration = 200.milliseconds
    }

    private val thinClientReconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    suspend fun connect(
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        onProgress: ((value: ProgressCountdown.ProgressEvent) -> Unit)? = null,
        checkCancelled: (() -> Unit)? = null,
        registerRestartWatcher: Boolean? = true
    ): ThinClientHandle {
        val workspace = devSpacesContext.devWorkspace
        devSpacesContext.addWorkspace(workspace)

        var thinClient: ThinClientHandle? = null
        var sessionCtx: ThinClientSessionContext? = null

        return try {
            val (remoteIdeServer, remoteIdeServerStatus) = waitForWorkspaceReady(
                onProgress = onProgress,
                checkCancelled = checkCancelled,
            )

            val joinLink = remoteIdeServerStatus.joinLink
                ?: throw IOException("Could not connect, workspace IDE is not ready. No join link present.")

            checkCancelled?.invoke()
            onProgress?.invoke(ProgressCountdown.ProgressEvent(
                message = "Waiting for the workspace IDE client to start...")
            )

            val localPort = findFreePort()

            sessionCtx = ThinClientSessionContext(
                localPort = localPort,
                remoteIdeServer = remoteIdeServer,
                forwarder = null,
                onConnected = onConnected,
                onDisconnected = onDisconnected,
                onDevWorkspaceStopped = onDevWorkspaceStopped,
                checkCancelled = checkCancelled,
            )

            val forwardRecoveryProgress = ForwardRecoveryProgress(
                scope = thinClientReconnectScope,
                sessionCtx = sessionCtx,
                isWorkspaceRestartInProgress = ::isWorkspaceRestartInProgress,
                onCanceled = {
                    sessionCtx.intentionalDisconnect.set(true)
                    onClientClosed(thinClient, sessionCtx)
                },
            )

            val tracker = WorkspacePodTracker(
                remoteIdeServer,
                ::isWorkspaceRestartInProgress,
            )
            tracker.seed(remoteIdeServer.getPod())

            setupThinClientReconnect(
                remoteIdeServer = remoteIdeServer,
                sessionCtx = sessionCtx,
                forwardRecoveryProgress = forwardRecoveryProgress,
                getCurrentClient = { thinClient },
                startThinClient = ::startThinClient,
                onClientHandleReplaced = { newClient ->
                    thinClient = newClient
                    if (registerRestartWatcher == true) {
                        watchRestartAnnotation(newClient, workspace)
                    }
                },
                onClientClosed = this@DevSpacesConnection::onClientClosed,
                tracker = tracker,
            )

            val portForwardResolver = PortForwardPodResolver(
                tracker = tracker,
                sessionCtx = sessionCtx,
                forwardRecovery = forwardRecoveryProgress,
            )

            sessionCtx.forwarder = startForwarding(
                podResolver = portForwardResolver::resolve,
                pods = DevWorkspacePods(devSpacesContext.client),
                localPort = localPort
            )

            thinClient = startThinClient(joinLink, sessionCtx)

            if (registerRestartWatcher == true) {
                watchRestartAnnotation(thinClient, workspace)
            }

            onConnected()
            thinClient
        } catch (e: Exception) {
            cleanupOnFailure(
                client = thinClient,
                sessionCtx = sessionCtx,
                forwarder = sessionCtx?.forwarder,
                workspace = workspace,
                onDisconnected = onDisconnected,
            )
            throw e
        }
    }

    @Throws(Exception::class)
    private suspend fun waitForWorkspaceReady(
        onProgress: ((value: ProgressCountdown.ProgressEvent) -> Unit)?,
        checkCancelled: (() -> Unit)?,
    ): Pair<RemoteIDEServer, RemoteIDEServerStatus> {
        var remoteIdeServer: RemoteIDEServer? = null
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
                countdownSeconds = RemoteIDEServer.READY_TIMEOUT))

            remoteIdeServer = RemoteIDEServer(devSpacesContext)
            remoteIdeServerStatus = fetchServerStatus(remoteIdeServer, checkCancelled)

            checkCancelled?.invoke()
            if (!remoteIdeServerStatus.isReady) {
                if (Dialogs.ideNotResponding()) {
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

        return remoteIdeServer!! to remoteIdeServerStatus
    }

    private suspend fun fetchServerStatus(
        remoteIdeServer: RemoteIDEServer,
        checkCancelled: (() -> Unit)?
    ): RemoteIDEServerStatus = runCatching {
        remoteIdeServer.waitServerReady(checkCancelled)
        remoteIdeServer.fetchStatus(checkCancelled)
    }.getOrElse { e ->
        if (e.isCancellationException()) throw e
        RemoteIDEServerStatus.empty()
    }

    private fun cleanupOnFailure(
        client: ThinClientHandle?,
        sessionCtx: ThinClientSessionContext?,
        forwarder: Closeable?,
        workspace: DevWorkspace,
        onDisconnected: () -> Unit,
    ) {
        runCatching { client?.close() }
        if (sessionCtx != null) {
            onClientClosed(client, sessionCtx)
        } else {
            runCatching { forwarder?.close() }
            devSpacesContext.removeWorkspace(workspace)
            runCatching { onDisconnected() }
        }
    }

    private suspend fun startForwarding(
        podResolver: suspend () -> PodForwardResolution,
        pods: DevWorkspacePods,
        localPort: Int,
    ): Closeable {
        val forwarder = pods.forward(
            podResolver,
            localPort,
            5990,
            RemoteIDEServer.READY_TIMEOUT
        )
        pods.waitForForwardReady(localPort)
        return forwarder
    }

    private fun createSessionContext(
        localPort: Int,
        remoteIdeServer: RemoteIDEServer,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        checkCancelled: (() -> Unit)?,
    ): Pair<ThinClientSessionContext, WorkspacePodTracker> {
        val ctx = ThinClientSessionContext(
            localPort = localPort,
            remoteIdeServer = remoteIdeServer,
            forwarder = null,
            onConnected = onConnected,
            onDisconnected = onDisconnected,
            onDevWorkspaceStopped = onDevWorkspaceStopped,
            checkCancelled = checkCancelled,
        )

        val tracker = WorkspacePodTracker(
            remoteIdeServer,
            ::isWorkspaceRestartInProgress,
        )

        return ctx to tracker
    }

    /**
     * Wires [WorkspacePodTracker.onPodRoll] to [ThinClientReconnect.execute].
     *
     * Pod-roll routing (annotation absent) is decided in the tracker; this method only connects
     * the callback after [ThinClientReconnect] is constructed for the session.
     */
    private fun setupThinClientReconnect(
        remoteIdeServer: RemoteIDEServer,
        sessionCtx: ThinClientSessionContext,
        forwardRecoveryProgress: ForwardRecoveryProgress,
        getCurrentClient: () -> ThinClientHandle?,
        startThinClient: suspend (String, ThinClientSessionContext) -> ThinClientHandle,
        onClientHandleReplaced: (ThinClientHandle) -> Unit,
        onClientClosed: (ThinClientHandle?, ThinClientSessionContext) -> Unit,
        tracker: WorkspacePodTracker
    ) {
        val thinClientReconnect = ThinClientReconnect(
            remoteIdeServer = remoteIdeServer,
            sessionCtx = sessionCtx,
            getCurrentClient = getCurrentClient,
            startThinClient = startThinClient,
            onClientHandleReplaced = onClientHandleReplaced,
            onClientClosed = onClientClosed,
        )
        tracker.onPodRoll = { pod ->
            forwardRecoveryProgress.dismiss()
            thinClientReconnect.execute(pod)
        }
    }

    /**
     * Returns true when the DevWorkspace carries [DevWorkspacePatch.RESTART_KEY].
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

    @Suppress("UnstableApiUsage")
    private suspend fun startThinClient(joinLink: String, ctx: ThinClientSessionContext): ThinClientHandle {
        val effectiveJoinLink = joinLink.replace(":5990", ":${ctx.localPort}")
        val client = LinkedClientManager
            .getInstance()
            .startNewClient(
                Lifetime.Eternal,
                URI(effectiveJoinLink),
                "",
                ctx.onConnected,
                false
            )

        val finished = AtomicBoolean(false)

        ctx.checkCancelled?.invoke()
        attachClientListeners(client, finished, ctx) {
            onClientClosed(client, ctx)
        }

        client.onClientPresenceChanged.advise(client.lifetime) { finished.set(true) }

        thisLogger().warn(
            "Thin client: waiting for connection on local port ${ctx.localPort} (join link host redirected from :5990)"
        )
        val success = withTimeoutOrNull(THIN_CLIENT_TIMEOUT) {
            while (!finished.get()) {
                ctx.checkCancelled?.invoke()
                delay(THIN_CLIENT_POLL_DELAY)
            }
            true
        } ?: false

        check(success && client.clientPresent) {
            "Could not connect, workspace IDE is not ready (local port ${ctx.localPort}, clientPresent=${client.clientPresent})."
        }

        return client
    }

    @Suppress("UnstableApiUsage")
    private fun attachClientListeners(
        client: ThinClientHandle,
        finished: AtomicBoolean,
        session: ThinClientSessionContext,
        onClosed: () -> Unit,
    ) {
        client.clientClosed.advise(client.lifetime) {
            if (!session.reconnecting.get()) {
                onClosed()
            }
            finished.set(true)
        }
        client.clientFailedToOpenProject.advise(client.lifetime) {
            if (!session.reconnecting.get()) {
                onClosed()
            }
            finished.set(true)
        }
    }

    /**
     * Starts the **user-initiated restart** watch ([RestartDevWorkspaceAnnotationWatch] → [DevWorkspaceRestart]).
     * Complements pod-roll detection in [WorkspacePodTracker] / [ThinClientReconnect].
     */
    @Suppress("UnstableApiUsage")
    private fun watchRestartAnnotation(thinClient: ThinClientHandle, workspace: DevWorkspace) {
        val restartWatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        RestartDevWorkspaceAnnotationWatch(
            onRestartAnnotated(thinClient),
            devSpacesContext.client,
            workspace.namespace,
            workspace.name
        ).start(restartWatchScope)

        thinClient.lifetime.onTermination {
            restartWatchScope.cancel()
        }
    }

    @Suppress("UnstableApiUsage")
    private fun onRestartAnnotated(thinClient: ThinClientHandle): () -> Job {
        return {
            CoroutineScope(Dispatchers.IO).launch {
                DevWorkspaceRestart(devSpacesContext).execute(thinClient)
            }
        }
    }

    @Suppress("UnstableApiUsage")
    internal fun onClientClosed(client: ThinClientHandle?, session: ThinClientSessionContext) {
        CoroutineScope(Dispatchers.IO).launch {
            if (session.reconnecting.get()) {
                thisLogger().debug("Skipping disconnect cleanup during pod-roll reconnect")
                return@launch
            }
            thinClientReconnectScope.cancel()
            runCatching { client?.close() }
            val workspace = devSpacesContext.devWorkspace
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
                } else if (
                    session.intentionalDisconnect.get()
                    || session.remoteIdeServer.waitServerTerminated()
                ) {
                    DevWorkspaces(devSpacesContext.client)
                        .stop(workspace.namespace, workspace.name)
                        .also { session.onDevWorkspaceStopped() }
                }
            } finally {
                closeForwarder(session.forwarder)
                devSpacesContext.removeWorkspace(workspace)
                runCatching { session.onDisconnected() }
            }
        }
    }

    private fun closeForwarder(forwarder: Closeable?) {
        runCatching { forwarder?.close() }
            .onFailure { e -> thisLogger().debug("Failed to close port forwarder", e) }
    }

}
