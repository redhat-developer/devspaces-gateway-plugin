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
import com.redhat.devtools.gateway.ThinClientConnectException
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.util.messageWithoutPrefix
import com.redhat.devtools.gateway.util.showProgress
import com.redhat.devtools.gateway.view.ui.Dialogs
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val TIMEOUT_SECONDS_DELETE_PODS = 120

/**
 * Performs a DevWorkspace restart that the remote IDE requested by adding the
 * [DevWorkspace.RESTART_KEY] annotation.
 *
 * The remote IDE (integration plugin) requests the restart by adding to the workspace k8s resource:
 * * [DevWorkspace.RESTART_KEY]
 * * the devfile template in [DevWorkspace.PENDING_TEMPLATE_KEY].
 *
 * The session watch observes the new annotation, calls [execute], and this class carries the restart out:
 *
 * 1. Read the devfile template the remote IDE published
 * 2. Close the thin client connection
 * 3. Stop the workspace (`spec.started` = false)
 * 4. Wait for all pods to be deleted
 * 5. Apply the devfile template while the workspace is stopped
 * 6. Start the workspace (`spec.started` = true)
 * 7. Reconnect the IDE; then remove
 *    * [DevWorkspace.RESTART_KEY] and
 *    * [DevWorkspace.PENDING_TEMPLATE_KEY]
 *   in a `finally` block (once per attempt, success or failure).
 *
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

    /**
     * Submits the restart as a background task and returns without waiting for it: the task itself
     * blocks on [restart] and shows an error dialog on failure.
     */
    @Suppress("UnstableApiUsage")
    fun execute(thinClient: ThinClientHandle) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            null, "Restart workspace", true
        ) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        runBlocking(Dispatchers.IO) {
                            restart(thinClient, indicator)
                        }

                    } catch (e: Exception) {
                        thisLogger().error("Could not restart workspace $namespace/$workspaceName",e)
                        ApplicationManager.getApplication().invokeLater {
                            when {
                                // classified thin-client failures carry their own dialog title
                                e is ThinClientConnectException ->
                                    Dialogs.error(
                                        e.messageWithoutPrefix() ?: "Could not connect to workspace IDE",
                                        e.title
                                    )
                                // a refused request aborts the restart before the IDE is closed
                                e is InvalidRestartRequestException ->
                                    Dialogs.error(
                                        e.messageWithoutPrefix() ?: "Could not restart workspace",
                                        "Restart Workspace"
                                    )
                                else ->
                                    Dialogs.error(
                                        e.messageWithoutPrefix() ?: "Could not connect to workspace IDE",
                                        "Connection Error"
                                    )
                            }
                        }
                    } finally {
                        removeAnnotations()
                    }
                }
            }
        )
    }

    /**
     * Restarts the workspace with the devfile template the remote IDE (integration plugin) has added.
     *
     * The remote IDE writes [DevWorkspace.PENDING_TEMPLATE_KEY] together with
     * [DevWorkspace.RESTART_KEY]. The template is applied after the workspace stops and before it
     * starts again, so the DevWorkspace Operator does not roll the pod on its own.
     *
     * @param thinClient the connection to close and replace
     * @param indicator progress indicator stepped through the restart, or `null`
     */
    @Suppress("UnstableApiUsage")
    internal suspend fun restart(
        thinClient: ThinClientHandle,
        indicator: ProgressIndicator?
    ) {
        indicator.showProgress("Reading devfile...", 0.0)
        val pendingTemplate = readPendingTemplate()
            ?: throw InvalidRestartRequestException(
                "The restart request carries no devfile template. The workspace was not restarted."
            )

        indicator.showProgress("Closing IDE connection...", 0.1)
        close(thinClient)

        indicator.showProgress("Stopping workspace...", 0.2)
        stopWorkspaceAndWait()

        indicator.showProgress("Waiting for pods to terminate...", 0.4)
        waitForPodsDeleted(indicator)

        indicator.showProgress("Applying devfile...", 0.5)
        val templateApplied = applyTemplate(pendingTemplate)

        indicator.showProgress("Starting workspace...", 0.6)
        startWorkspaceAndWait()

        indicator.showProgress("Waiting for IDE to be ready...", 0.8)
        waitForIDEReady()

        indicator.showProgress("Connecting to IDE...", 1.0)
        connectIDE()

        if (!templateApplied) {
            showTemplateError()
        }
    }

    /**
     * Reads the template the remote IDE published, before anything is closed or stopped. A missing
     * or invalid payload aborts here, so the IDE stays connected and the workspace keeps running.
     */
    private fun readPendingTemplate(): Map<String, Any?>? {
        val workspace = workspaces.get(namespace, workspaceName)
        val content = workspace.pendingTemplatePayload
        val template = PendingTemplateParser.parse(content)
        if (template != null) {
            thisLogger().debug("Read devfile template published for $namespace/$workspaceName")
        }
        return template
    }

    /**
     * Applies the published template while the workspace is stopped, so the DevWorkspace Operator
     * does not roll the pod on its own. A failure does not keep the workspace down: it is started
     * with its previous template and the user is told.
     */
    private fun applyTemplate(template: Map<String, Any?>): Boolean {
        return try {
            workspaces.setSpecTemplate(namespace, workspaceName, template)
            thisLogger().debug("Applied devfile template to $namespace/$workspaceName")
            true
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            thisLogger().warn("Could not apply devfile template to $namespace/$workspaceName", e)
            false
        }
    }

    private fun showTemplateError() {
        ApplicationManager.getApplication()?.invokeLater {
            Dialogs.error(
                "Workspace $namespace/$workspaceName was restarted without the devfile changes.",
                "Restart Workspace"
            )
        }
    }

    private suspend fun waitForIDEReady() {
        thisLogger().debug("Waiting for IDE server to be ready...")
        createRemoteIDEServer().waitServerReady()
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
            // The annotation of the restart we are completing is still present here and is
            // removed in the finally block. The session watch treats it as stale state and
            // cleans it up, so later restart requests are observed as transitions again.
            registerRestartWatcher = true
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
        @Suppress("ConvertLongToDuration")
        delay(1000L) // Give time for port forwarder cleanup
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
            @Suppress("ConvertLongToDuration")
            withTimeout(TIMEOUT_SECONDS_DELETE_PODS * 1000L) {
                while (true) {
                    indicator?.checkCanceled()
                    val pods = fetchPodsWithRetry(labelSelector)
                    if (pods.isEmpty()) break
                    delay(2000L)
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
            @Suppress("ConvertLongToDuration")
            delay(1000L)
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

    private fun removeAnnotations() {
        runCatching {
            if (workspaces.hasRestartAnnotation(namespace, workspaceName)) {
                workspaces.removeRestartAnnotation(namespace, workspaceName)
                thisLogger().debug("Removed restart annotation from $namespace/$workspaceName")
            } else {
                thisLogger().debug("Restart annotation already removed from $namespace/$workspaceName")
            }
        }.onFailure { e ->
            thisLogger().debug("Failed to remove restart annotation from $namespace/$workspaceName", e)
        }
        runCatching {
            workspaces.removePendingTemplate(namespace, workspaceName)
        }.onFailure { e ->
            thisLogger().debug("Failed to remove pending devfile template from $namespace/$workspaceName", e)
        }
    }
}
