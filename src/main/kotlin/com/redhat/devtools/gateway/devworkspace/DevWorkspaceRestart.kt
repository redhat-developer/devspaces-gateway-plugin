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
package com.redhat.devtools.gateway.devworkspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.wm.WindowManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.redhat.devtools.gateway.DevSpacesConnection
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.util.messageWithoutPrefix
import com.redhat.devtools.gateway.util.updateProgress
import com.redhat.devtools.gateway.util.checkProgressCanceled
import com.redhat.devtools.gateway.util.WorkspaceSessionProgress
import com.redhat.devtools.gateway.view.ui.Dialogs
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

private const val TIMEOUT_SECONDS_DELETE_PODS = 120

/**
 * Restarts a DevWorkspace after a **user-initiated** restart from the Remote IDE.
 *
 * ## Session recovery routing
 *
 * Entry point: [RestartDevWorkspaceAnnotationWatch] when [DevWorkspacePatch.RESTART_KEY] appears
 * on the DevWorkspace CR. This is the annotated-restart path; it does **not** use
 * [com.redhat.devtools.gateway.WorkspacePodTracker] or [com.redhat.devtools.gateway.ThinClientReconnect].
 *
 * While the annotation is set, [WorkspacePodTracker] skips pod-roll reconnect so both handlers
 * never run concurrently (pod rolls during stop/start are expected but ignored by the tracker).
 *
 * Progress title: "Restart workspace". Steps use [com.redhat.devtools.gateway.util.WorkspaceSessionProgress].
 *
 * Restart sequence:
 * 1. Close the thin client connection
 * 2. Stop the workspace (spec.started = false)
 * 3. Wait for all pods to be deleted
 * 4. Start the workspace (spec.started = true)
 * 5. Reconnect the IDE via a new [DevSpacesConnection.connect]
 *
 * [execute] removes the restart annotation in a `finally` block (success or failure).
 */
class DevWorkspaceRestart(
    private val devSpacesContext: DevSpacesContext,
    private val workspaces: DevWorkspaces = DevWorkspaces(devSpacesContext.client),
    private val pods: DevWorkspacePods = DevWorkspacePods(devSpacesContext.client),
    private val createRemoteIDEServer: () -> RemoteIDEServer = { RemoteIDEServer(devSpacesContext) },
    private val createDevSpacesConnection: () -> DevSpacesConnection = { DevSpacesConnection(devSpacesContext) }
) {

    private val namespace get() = devSpacesContext.devWorkspace.namespace
    private val workspaceName get() = devSpacesContext.devWorkspace.name

    @Suppress("UnstableApiUsage")
    suspend fun execute(thinClient: ThinClientHandle) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            null, "Restart workspace", true
        ) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    try {
                        runBlocking(Dispatchers.IO) {
                            restart(thinClient, indicator)
                        }

                    } catch (e: Exception) {
                        thisLogger().error("Could not restart workspace $namespace/$workspaceName",e)
                        ApplicationManager.getApplication().invokeLater {
                            Dialogs.error(e.messageWithoutPrefix()?: "Could not connect to workspace IDE","Connection Error")
                        }
                    } finally {
                        removeAnnotation()
                    }
                }
            }
        )
    }

    @Suppress("UnstableApiUsage")
    internal suspend fun restart(
        thinClient: ThinClientHandle,
        indicator: ProgressIndicator?
    ) {
        indicator?.updateProgress(WorkspaceSessionProgress.CLOSING_IDE, 0.0)
        close(thinClient)

        indicator?.updateProgress(WorkspaceSessionProgress.STOPPING_WORKSPACE, 0.2)
        stopWorkspaceAndWait()

        indicator?.updateProgress(WorkspaceSessionProgress.WAITING_PODS_TERMINATE, 0.4)
        waitForPodsDeleted(indicator)

        indicator?.updateProgress(WorkspaceSessionProgress.STARTING_WORKSPACE, 0.6)
        startWorkspaceAndWait()

        indicator?.updateProgress(WorkspaceSessionProgress.WAITING_FOR_IDE, 0.8)
        waitForIDEReady(indicator)

        indicator?.updateProgress(WorkspaceSessionProgress.CONNECTING_TO_IDE, 1.0)
        connectIDE()
    }

    private suspend fun waitForIDEReady(indicator: ProgressIndicator?) {
        thisLogger().debug("Waiting for IDE server to be ready...")
        val checkCancelled = indicator?.let { ind -> { checkProgressCanceled(ind) } }
        createRemoteIDEServer().awaitJoinLink(checkCancelled = checkCancelled)
        thisLogger().debug("IDE server is ready")
    }

    /**
     * Reconnects via [DevSpacesConnection.connect]. That method throws if the IDE client never
     * becomes ready; [execute] catches those failures and shows an error dialog. The disconnect
     * callback only runs when the client closes later.
     */
    private suspend fun connectIDE() {
        createDevSpacesConnection().connect(
            ::onConnected,
            {
                thisLogger().warn("IDE connection failed")
            },
            {
                thisLogger().debug("Workspace stopped after connection")
            },
            registerRestartWatcher = false
        )
    }

    private fun onConnected(): () -> Unit = {
        ApplicationManager.getApplication().invokeLater {
            runCatching {
                val refreshedWorkspace = DevWorkspaces(devSpacesContext.client)
                    .get(namespace, workspaceName)
                devSpacesContext.devWorkspace = refreshedWorkspace
                repaintAllViews()
            }.onFailure {
                thisLogger().warn("Failed to refresh UI after reconnect", it)
            }
        }
        thisLogger().debug("IDE connected successfully")
    }

    private fun repaintAllViews() {
        val views = WindowManager.getInstance().allProjectFrames
        views.forEach {
            it.component.repaint()
        }
    }

    @Suppress("UnstableApiUsage")
    private suspend fun close(thinClient: ThinClientHandle) {
        thisLogger().debug("Closing thin client for $namespace/$workspaceName")
        thinClient.close()
        delay(PORT_FORWARDER_CLEANUP_WAIT)
    }

    private fun stopWorkspaceAndWait() {
        thisLogger().debug("Stopping workspace and waiting...")
        workspaces.stopAndWait(
            namespace,
            workspaceName,
            DevWorkspaces.RUNNING_TIMEOUT
        )

        thisLogger().debug("Workspace stopped")
    }

    private fun startWorkspaceAndWait() {
        thisLogger().debug("Starting workspace and waiting...")
        workspaces.startAndWait(
            namespace,
            workspaceName
        )
        thisLogger().debug("Workspace started and running")
    }

    private suspend fun waitForPodsDeleted(indicator: ProgressIndicator?) {
        val labelSelector = "${DevWorkspacePods.WORKSPACE_LABEL_KEY}=$workspaceName"

        try {
            withTimeout(TIMEOUT_SECONDS_DELETE_PODS.seconds) {
                while (true) {
                    indicator?.let { checkProgressCanceled(it) }
                    val pods = fetchPodsWithRetry(labelSelector)
                    if (pods.isEmpty()) break
                    delay(POD_DELETION_POLL_DELAY)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Timeout waiting for pods deletion. Remaining pods: ${getRemainingPodNames(labelSelector)}", e)
        }
    }

    private suspend fun fetchPodsWithRetry(labelSelector: String): List<V1Pod> {
        return try {
            pods.list(namespace, labelSelector).items
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            thisLogger().warn("Failed to list pods, retrying...", e)
            delay(POD_FETCH_RETRY_DELAY)
            fetchPodsWithRetry(labelSelector)
        }
    }

    private suspend fun getRemainingPodNames(labelSelector: String): List<String?> {
        return try {
            pods.list(namespace, labelSelector).items.map { it.metadata?.name }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun removeAnnotation() {
        runCatching {
            if (workspaces.isRestarting(namespace, workspaceName)) {
                workspaces.removeRestarting(namespace, workspaceName)
                thisLogger().debug("Removed restart annotation from $namespace/$workspaceName")
            } else {
                thisLogger().debug("Restart annotation already removed from $namespace/$workspaceName")
            }
        }.onFailure { e ->
            thisLogger().debug("Failed to remove restart annotation from $namespace/$workspaceName", e)
        }
    }

    companion object {
        val POD_DELETION_POLL_DELAY: kotlin.time.Duration = 2.seconds
        val POD_FETCH_RETRY_DELAY: kotlin.time.Duration = 1.seconds
        val PORT_FORWARDER_CLEANUP_WAIT: kotlin.time.Duration = 1.seconds
    }
}
