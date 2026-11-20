/*
 * Copyright (c) 2026 Red Hat, Inc.
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
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import com.redhat.devtools.gateway.openshift.PortForwardSession
import com.redhat.devtools.gateway.openshift.PortForwarder
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.view.ui.Dialogs
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.IOException
import java.net.URI
import java.util.concurrent.CancellationException

/**
 * Recovery wiring of one live thin-client connection.
 *
 * It builds the [ThinClientRecoveryWatcher] of the session and starts it from the forwarder's
 * remote-end signal; [com.redhat.devtools.gateway.DevSpacesConnection] keeps the connection
 * lifecycle that owns it. Recovery observes the existing Guest only: it resolves the current
 * workspace pod itself and prods that Guest to re-dial. It never starts a new client and never
 * closes one.
 *
 * @param devSpacesContext the connection's context, used to resolve pods and build fresh links.
 * @param clientWindowFocus brings the Guest window to the front on a loss and on a recovery.
 * @param portForwarder probes whether the workspace's pod can be reached right now.
 * @param isTearingDown whether the connection already began tearing down: a connection that ends
 *   then must not start a recovery.
 */
internal class ThinClientRecovery(
    private val devSpacesContext: DevSpacesContext,
    private val clientWindowFocus: ClientWindowFocus,
    private val portForwarder: PortForwarder,
    private val isTearingDown: () -> Boolean
) {

    /**
     * Watches the Guest's re-dial while the connection is lost. Set once the session is live and
     * cleared when recovery ends; the connection reads [stopRecovery]'s state to withhold the
     * workspace auto-stop (§7.9) and [startRecovery] starts it from the forwarder's remote-end
     * signal.
     */
    @Volatile
    private var recoveryWatcher: ThinClientRecoveryWatcher? = null

    /** Owns the recovery loop, so cancelling it ends recovery with the client session. */
    @Volatile
    private var recoveryScope: CoroutineScope? = null

    /**
     * Builds the recovery watcher of this session. Nothing starts yet: the Guest may be perfectly
     * fine. [startRecovery] starts it on the forward's remote-end signal; the client's lifetime and
     * [stopRecovery] stop it.
     *
     * @param sessionPod the pod [localJoinLink] was built against; when a different pod serves the
     *   workspace, the link's session token is unknown to it and recovery refreshes the link.
     */
    fun prepareRecovery(
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
     * The forward ended: the Guest lost its connection. Start the recovery episode that prods it to
     * re-dial; starting is idempotent, so a session that keeps ending does not spawn a second loop.
     */
    fun onRemoteEnd(client: ThinClientHandle?) {
        startRecovery(client)
    }

    /**
     * Starts a recovery episode and, only when a new episode actually began, brings the Guest window
     * to the front once: the Guest repaints its connection-status widget only while focused, so the
     * loss has to become visible.
     *
     * Focusing per connection end would steal the window instead: during an outage the Guest's
     * dials end every ~4.5 s (live-measured) while [ClientWindowFocus] throttles at 5 s, so the
     * window would jump to the front every few seconds for the whole outage.
     */
    private fun startRecovery(client: ThinClientHandle?) {
        val watcher = recoveryWatcher
        val scope = recoveryScope
        // teardown already running: a connection that ends now must not start a recovery
        if (isTearingDown() || watcher == null || scope == null) {
            return
        }
        if (watcher.start(scope)) {
            client?.let(clientWindowFocus::requestFocus)
        }
    }

    /**
     * Stops recovery and reports whether it was running.
     *
     * The running state is read *before* the watcher is stopped, so stopping does not erase it; the
     * return value carries it to the caller. The connection's teardown uses it to withhold the
     * workspace auto-stop (§7.9): a Guest closed during recovery must not stop a workspace that may
     * come back once the outage clears. Only the remote `stop()` is withheld — the rest of the
     * teardown is unchanged.
     */
    fun stopRecovery(): Boolean {
        val watcher = recoveryWatcher
        val wasActive = watcher?.isActive == true
        watcher?.stop()
        recoveryWatcher = null
        recoveryScope?.cancel()
        recoveryScope = null
        return wasActive
    }

    /**
     * Installs the recovery watcher of this session; [prepareRecovery] wires the production one.
     * Internal so a test can emulate a recovery in progress without a cluster.
     *
     * @param scope owns the recovery loop; a test that lets a remote end start the watcher must pass
     *   one, exactly as [prepareRecovery] does.
     */
    fun installRecoveryWatcher(
        watcher: ThinClientRecoveryWatcher?,
        scope: CoroutineScope? = null
    ) {
        recoveryWatcher = watcher
        recoveryScope = scope
    }

    /**
     * The pod that serves [workspace] right now. Runs on the watcher's IO coroutine; a failure is
     * retried on its next poll.
     */
    fun findRunningPod(workspace: DevWorkspace): V1Pod? =
        DevWorkspacePods(devSpacesContext.client).findNewestRunning(
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
            "Connection Error",
            ModalityState.any()
        )
    }
}
