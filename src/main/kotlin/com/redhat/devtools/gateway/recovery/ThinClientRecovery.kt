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
package com.redhat.devtools.gateway.recovery

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.ThinClientJoinLink
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import com.redhat.devtools.gateway.openshift.PortForwardSession
import com.redhat.devtools.gateway.openshift.ProbePortForward
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
 * Builds the session's [ThinClientRecoveryWatcher] and starts it from the forwarder's remote-end
 * signal; [com.redhat.devtools.gateway.DevSpacesConnection] owns the connection lifecycle. Recovery
 * observes the existing Guest only: resolves the current workspace pod itself, forces that Guest to
 * re-dial. Never starts a new client, never closes one.
 *
 * @param devSpacesContext connection's context: resolves pods, builds fresh links.
 * @param probePortForward probes whether the workspace pod is reachable now.
 * @param isTearingDown connection began tearing down? Then an ending connection must not start recovery.
 */
@Suppress("TooManyFunctions") // one session's recovery wiring: each function is a small, named step
internal class ThinClientRecovery(
    private val devSpacesContext: DevSpacesContext,
    private val probePortForward: ProbePortForward,
    private val isTearingDown: () -> Boolean
) {

    /**
     * Watches Guest's re-dial while connection is lost. Set once session is live, cleared when
     * recovery ends; connection reads [stopRecovery]'s state to withhold workspace auto-stop (§7.9)
     * and [startRecovery] starts it from the forwarder's remote-end signal.
     */
    @Volatile
    private var recoveryWatcher: ThinClientRecoveryWatcher? = null

    /** Owns the recovery loop: cancelling it ends recovery with the client session. */
    @Volatile
    private var recoveryScope: CoroutineScope? = null

    /** Guards the recovery state so a client close and teardown read/clear it atomically. */
    private val recoveryStateLock = Any()

    /**
     * Recovery-active state captured when the client's lifetime ended during recovery.
     *
     * A Guest close that happens while recovery runs terminates the client *before* teardown reads
     * the state. Capturing it here (instead of losing it with the watcher) lets the teardown that
     * this close initiates still withhold the workspace auto-stop (§7.9).
     */
    private var recoveryActiveOnClientClose: Boolean = false

    /**
     * Builds session's recovery watcher. Nothing starts yet: Guest may be fine. [startRecovery]
     * starts it on forward's remote-end signal; client lifetime and [stopRecovery] stop it.
     *
     * @param sessionPod pod [localJoinLink] was built against; different serving pod → link's session
     *   token unknown to it → recovery refreshes the link.
     */
    @Suppress("UnstableApiUsage")
    fun prepareRecovery(
        workspace: DevWorkspace,
        thinClient: ThinClientHandle,
        session: PortForwardSession,
        localJoinLink: URI,
        sessionPod: V1Pod
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val environment = ThinClientRecoveryWatcher.RecoveryEnvironment(
            resolvePod = {
                DevWorkspacePods(devSpacesContext.client)
                    .findServingPod(workspace.namespace, workspace.name)
            },
            probe = { pod ->
                probePortForward(pod, ThinClientJoinLink.REMOTE_IDE_PORT)
            },
            cachedLink = localJoinLink,
            sessionPod = sessionPod,
            refreshLink = refreshJoinLink(workspace, session.localPort),
            reconnectInPlace = reconnectInPlace(thinClient),
            forceReconnect = forceReconnectJoinLink(thinClient),
            session = session,
            isClientPresent = { thinClient.clientPresent },
            onRecovered = { reportRecovered(thinClient) },
            onRecoveryFailed = { reportRecoveryFailed(thinClient, workspace) }
        )
        synchronized(recoveryStateLock) {
            recoveryActiveOnClientClose = false
            recoveryScope = scope
            recoveryWatcher = ThinClientRecoveryWatcher(environment)
        }
        thinClient.lifetime.onTermination {
            // The close this callback reports also initiates teardown: stop the loop but keep the
            // active state for stopRecovery so teardown still withholds the auto-stop (§7.9).
            synchronized(recoveryStateLock) {
                if (recoveryWatcher?.isActive() == true) {
                    recoveryActiveOnClientClose = true
                }
                detachWatcherLocked()
            }
        }
    }

    /**
     * Forward ended: Guest lost its connection. Starts the episode forcing a re-dial; idempotent, so
     * a repeatedly ending session does not spawn a second loop.
     *
     * Guest window is brought to front once, only when a new episode actually began: Guest repaints
     * its connection-status widget only while focused, so the loss must become visible.
     *
     * Focus is gated by this state change, not by time: dial churn ends connections every ~4.5 s
     * (live-measured), so focusing per connection end would steal the window every few seconds for
     * the whole outage. The episode latch already bounds the focus to once per outage; a time
     * throttle on top could swallow that single focus when an episode begins right after another
     * transition focused (a settling session's recovery), and the loss would never be painted.
     */
    @Suppress("UnstableApiUsage")
    internal fun startRecovery(client: ThinClientHandle?) {
        val watcher = recoveryWatcher
        val scope = recoveryScope
        // teardown already running: a connection that ends now must not start a recovery
        if (isTearingDown()
            || watcher == null
            || scope == null) {
            return
        }
        if (watcher.start(scope)) {
            runCatching { client?.focusClientWindow() }
        }
    }

    /**
     * Stops recovery and reports whether it was active.
     *
     * Active state read *before* stop, so stopping does not erase it; return value carries it. The
     * state captured when the client's lifetime ended during recovery is included and consumed here,
     * so a close that terminates the client before teardown still reports the episode. Teardown uses
     * it to withhold workspace auto-stop (§7.9): Guest closed during recovery must not stop a
     * workspace that may come back once the outage clears. Only remote `stop()` is withheld — rest
     * of teardown unchanged.
     */
    fun stopRecovery(): Boolean = synchronized(recoveryStateLock) {
        val wasActive = recoveryWatcher?.isActive() == true || recoveryActiveOnClientClose
        recoveryActiveOnClientClose = false
        detachWatcherLocked()
        wasActive
    }

    /**
     * Stops the loop and clears its wiring. Callers decide what to report: the captured
     * [recoveryActiveOnClientClose] is consumed by [stopRecovery], not here.
     */
    private fun detachWatcherLocked() {
        recoveryWatcher?.stop()
        recoveryWatcher = null
        recoveryScope?.cancel()
        recoveryScope = null
    }

    /**
     * Installs session's recovery watcher; [prepareRecovery] wires the production one. Internal so a
     * test can emulate a recovery in progress without a cluster.
     *
     * @param scope owns recovery loop; a test letting a remote end start the watcher must pass one,
     *   exactly as [prepareRecovery] does.
     */
    fun installRecoveryWatcher(
        watcher: ThinClientRecoveryWatcher?,
        scope: CoroutineScope? = null
    ) {
        synchronized(recoveryStateLock) {
            recoveryActiveOnClientClose = false
            recoveryWatcher = watcher
            recoveryScope = scope
        }
    }

    /**
     * Fresh local join link for the *current* pod, or `null` while none can be built yet.
     *
     * [RemoteIDEServer] construction resolves pod + container atomically; its [IOException] = "no
     * running, ready pod yet" (not a recovery failure — watcher keeps polling). [RemoteIDEServer.getStatus]
     * execs IDE server status once, bounded by [RemoteIDEServer.STATUS_EXEC_TIMEOUT], off the EDT.
     */
    @Suppress("TooGenericExceptionCaught") // a malformed link or a failed exec only means "poll again"
    private fun refreshJoinLink(workspace: DevWorkspace, localPort: Int): suspend () -> URI? = {
        try {
            freshJoinLink(workspace, localPort)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            thisLogger().debug("No running, ready pod to build a join link from; retrying.", e)
            null
        } catch (e: Exception) {
            // malformed link or failed exec must not end recovery: next poll retries
            if (e.isCancellationException()) throw e
            thisLogger().debug("Could not build a fresh join link; retrying.", e)
            null
        }
    }

    /**
     * Local join link server reports for [workspace] now, or `null` while none usable.
     *
     * Server answering but reporting no usable link → logged at info: recovery waits instead of
     * forcing a reconnect, so "why is it not reconnecting?" is answerable from the log.
     */
    private suspend fun freshJoinLink(workspace: DevWorkspace, localPort: Int): URI? {
        val status = RemoteIDEServer(devSpacesContext, workspace).getStatus()
        if (!status.isReady || status.joinLink == null) {
            thisLogger().info(
                "Workspace '${workspace.name}' reports no join link yet " +
                    "(ready=${status.isReady}); recovery waits for a fresh link."
            )
            return null
        }
        return ThinClientJoinLink.toLocal(URI(status.joinLink), localPort)
    }

    /**
     * Frame-preserving lever: `notifyReconnect()` makes Guest re-dial its last stored join link
     * without disposing the project. Used whenever link did not change — network cut, VPN drop,
     * laptop sleep — so window and frame stay.
     *
     * A throwing handle is caught by the watcher, which then checks client presence.
     */
    @Suppress("UnstableApiUsage")
    private fun reconnectInPlace(thinClient: ThinClientHandle): suspend () -> Unit = {
        thinClient.notifyReconnect()
    }

    /**
     * Frame-swapping lever (verified in Guest): `updateJoinLink(forceReconnect = true)` drops the RD
     * link and re-dials the given link. Disposes + re-opens the project → only when link really
     * changed: replaced pod, or restarted IDE server whose session token the cached link cannot address.
     */
    @Suppress("UnstableApiUsage")
    private fun forceReconnectJoinLink(thinClient: ThinClientHandle): suspend (URI) -> Unit = { link ->
        thinClient.updateJoinLink(link, true)
    }

    private fun reportRecovered(thinClient: ThinClientHandle) {
        thisLogger().info(
            "Recovered the connection to the workspace; the JetBrains Client keeps its window."
        )
        // Guest repaints connection status when window is brought to front
        runCatching { thinClient.focusClientWindow() }
    }

    /**
     * Recovery definitively over. Reported only if Guest still exists: a gone Guest was closed by
     * user or platform's websocket timeout → nobody to tell. Nothing relaunched, no client closed;
     * workspace stop decision is no longer ours.
     */
    @Suppress("UnstableApiUsage")
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
        // down -> failed is a state change too: surface the final state in the Guest before
        // telling the user in the Gateway frame
        runCatching { thinClient.focusClientWindow() }
        Dialogs.error(
            "The connection to workspace '${workspace.name}' could not be restored. " +
                "Close the client and connect again.",
            "Connection Error",
            ModalityState.any()
        )
    }
}
