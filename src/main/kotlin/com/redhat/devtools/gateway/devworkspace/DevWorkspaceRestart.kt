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
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.wm.WindowManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.redhat.devtools.gateway.DevSpacesConnection
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.ThinClientConnectException
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import com.redhat.devtools.gateway.openshift.isNoHttpStatus
import com.redhat.devtools.gateway.openshift.isRequestTimeout
import com.redhat.devtools.gateway.openshift.isRetryable
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.util.messageWithoutPrefix
import com.redhat.devtools.gateway.view.ui.Dialogs
import com.redhat.devtools.gateway.view.ui.ProgressDialogParent
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CancellationException

private const val TIMEOUT_SECONDS_DELETE_PODS = 120
private const val REQUEST_READ_ATTEMPTS = 3
private const val REQUEST_READ_RETRY_DELAY = 1000L
private const val TEMPLATE_APPLY_ATTEMPTS = 2

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
 * 1. Read the devfile template the remote IDE published (optional; older editor images only set
 *    [DevWorkspace.RESTART_KEY])
 * 2. Close the thin client connection
 * 3. Stop the workspace (`spec.started` = false)
 * 4. Wait for all pods to be deleted
 * 5. Apply the devfile template, when published, while the workspace is stopped
 * 6. Start the workspace (`spec.started` = true)
 * 7. Reconnect the IDE; then remove
 *    * [DevWorkspace.RESTART_KEY] and
 *    * [DevWorkspace.PENDING_TEMPLATE_KEY]
 *   in a `finally` block (once per attempt, success or failure).
 *
 */
@Suppress("TooManyFunctions") // the restart is one cohesive multi-step operation
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
     * The workspace a restart belongs to, captured before the flow suspends. The shared
     * [DevSpacesContext.devWorkspace] can change while the restart runs in the background, so
     * every step must resolve the workspace through this captured identity instead.
     */
    private data class WorkspaceIdentity(val namespace: String, val name: String) {
        override fun toString(): String = "$namespace/$name"
    }

    private data class RestartRequest(
        val workspace: WorkspaceIdentity,
        val template: Map<String, Any?>?
    )

    /**
     * Schedules the restart on the EDT and returns without waiting for it.
     *
     * Progress uses [ProgressManager.runProcessWithProgressSynchronously] with a `null` project —
     * the same undecorated, non-resizable progress dialog as workspace connect in
     * [com.redhat.devtools.gateway.view.steps.DevSpacesWorkspacesStepView].
     *
     * The dialog is parented to the Gateway / Remote Development window via
     * [ProgressDialogParent.component]. With no parent, the platform uses the
     * `JOptionPane` root frame (the host IDE), so the progress sits behind the
     * Gateway window.
     *
     * Must run on the EDT: [ProgressManager.runProcessWithProgressSynchronously] shows its dialog
     * from the calling thread.
     */
    @Suppress("UnstableApiUsage")
    fun execute(thinClient: ThinClientHandle) {
        ApplicationManager.getApplication().invokeLater(
            {
                ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    {
                        thisLogger().debug("Restart task started for $namespace/$workspaceName")
                        val progress = ProgressManager.getInstance().progressIndicator
                        runBlocking(Dispatchers.IO) {
                            executeRestart(thinClient, progress)
                        }
                    },
                    "Restart Workspace",
                    true,
                    null,
                    ProgressDialogParent.component()
                )
            },
            ModalityState.any()
        )
    }

    /**
     * Performs [restart] and reports failures. Exposed for tests that need to await completion.
     *
     * @param progress the platform progress indicator, or `null` when running without one
     */
    @Suppress("UnstableApiUsage", "TooGenericExceptionCaught") // any failure is reported to the user
    internal suspend fun executeRestart(
        thinClient: ThinClientHandle,
        progress: ProgressIndicator? = null
    ) {
        try {
            restart(thinClient, progress)
        } catch (e: ThinClientConnectException) {
            reportFailure(e, e.title, "Could not connect to workspace IDE")
        } catch (e: InvalidRestartRequestException) {
            reportFailure(e, "Restart Workspace", "Could not restart workspace")
        } catch (e: RestartRequestReadException) {
            reportFailure(e, "Restart Workspace", "Could not read the restart request")
        } catch (e: TimeoutCancellationException) {
            reportFailure(e, "Connection Error", "Could not connect to workspace IDE")
        } catch (e: CancellationException) {
            thisLogger().debug("Restart cancelled for $namespace/$workspaceName")
            throw e
        } catch (e: Exception) {
            reportFailure(e, "Connection Error", "Could not connect to workspace IDE")
        } finally {
            removeAnnotations()
        }
    }

    private fun reportFailure(e: Exception, title: String, fallbackMessage: String) {
        thisLogger().error("Could not restart workspace $namespace/$workspaceName", e)
        Dialogs.error(e.messageWithoutPrefix() ?: fallbackMessage, title)
    }

    /**
     * Restarts the workspace with the devfile template the remote IDE (integration plugin) has added.
     *
     * The remote IDE writes [DevWorkspace.PENDING_TEMPLATE_KEY] together with
     * [DevWorkspace.RESTART_KEY]. The template is applied after the workspace stops and before it
     * starts again, so the DevWorkspace Operator does not roll the pod on its own.
     *
     * An older editor image may only set [DevWorkspace.RESTART_KEY]: the restart then proceeds
     * without applying a devfile template and a warning is logged.
     *
     * @param thinClient the connection to close and replace
     * @param progress progress indicator stepped through the restart, or `null`
     */
    @Suppress("UnstableApiUsage", "MagicNumber") // progress fractions are self-explanatory here
    internal suspend fun restart(
        thinClient: ThinClientHandle,
        progress: ProgressIndicator? = null
    ) {
        reportProgress(progress, "Reading devfile...", 0.0)
        val request = readRestartRequest()
        if (request.template == null) {
            thisLogger().warn(
                "Restart request for ${request.workspace} carries no devfile template; " +
                    "restarting without devfile changes"
            )
        }

        reportProgress(progress, "Closing IDE connection...", 0.1)
        close(thinClient)

        reportProgress(progress, "Stopping workspace...", 0.2)
        stopWorkspaceAndWait(request.workspace)

        reportProgress(progress, "Waiting for pods to terminate...", 0.4)
        waitForPodsDeleted(request.workspace, progress)

        var templateFailed = false
        if (request.template != null) {
            reportProgress(progress, "Applying devfile...", 0.5)
            templateFailed = !applyTemplate(request.workspace, request.template)
        }

        reportProgress(progress, "Starting workspace...", 0.6)
        startWorkspaceAndWait(request.workspace)

        reportProgress(progress, "Waiting for IDE to be ready...", 0.8)
        waitForIDEReady()

        reportProgress(progress, "Connecting to IDE...", 1.0)
        connectIDE()

        if (templateFailed) {
            showTemplateError()
        }
    }

    private fun reportProgress(
        indicator: ProgressIndicator?,
        message: String,
        fraction: Double
    ) {
        indicator ?: return
        indicator.isIndeterminate = false
        indicator.fraction = fraction
        indicator.text = message
    }

    /**
     * Reads the template the remote IDE published, before anything is closed or stopped. A missing
     * template is not an error (older editor images only request a restart); an invalid payload
     * aborts here, so the IDE stays connected and the workspace keeps running. Transient API
     * failures are retried so a restart request isn't dropped on a single read failure.
     *
     * The workspace identity is captured here, before any suspension, and is returned with the
     * template so the rest of the restart cannot be redirected to a different workspace.
     */
    private suspend fun readRestartRequest(): RestartRequest {
        val identity = WorkspaceIdentity(namespace, workspaceName)
        val workspace = readWorkspaceForRestart(identity)
        val template = PendingTemplateParser.parse(workspace.pendingTemplatePayload)
        if (template != null) {
            thisLogger().debug("Read devfile template published for $identity")
        }
        return RestartRequest(identity, template)
    }

    private suspend fun readWorkspaceForRestart(identity: WorkspaceIdentity): DevWorkspace {
        var lastFailure: ApiException? = null
        repeat(REQUEST_READ_ATTEMPTS) { attempt ->
            try {
                return workspaces.get(identity.namespace, identity.name)
            } catch (e: ApiException) {
                lastFailure = e
                thisLogger().warn(
                    "Could not read restart request for $identity; retrying.",
                    e
                )
                if (attempt < REQUEST_READ_ATTEMPTS - 1) {
                    @Suppress("ConvertLongToDuration")
                    delay(REQUEST_READ_RETRY_DELAY)
                }
            }
        }
        throw RestartRequestReadException(
            "Could not read the restart request for $identity",
            lastFailure
        )
    }

    /** Outcome of an attempt to commit the published template. */
    private enum class TemplateApplication {
        /** The template is committed; the workspace will start with it. */
        APPLIED,

        /** The apiserver rejected the patch; the workspace keeps its previous template. */
        NOT_APPLIED,

        /** The patch may or may not have committed, e.g. after a timed-out response. */
        AMBIGUOUS
    }

    /**
     * Applies the published template while the workspace is stopped, so the DevWorkspace Operator
     * does not roll the pod on its own.
     *
     * An ambiguous failure, such as a timed-out response, does not prove the patch was rejected:
     * the apiserver may have committed it. The replace is idempotent, so it is retried before the
     * template is reported as unapplied. Only an explicit apiserver rejection makes the restart
     * fall back to the previous template; an outcome that stays inconclusive is logged instead of
     * reported as a failure, because the workspace starts with whatever the apiserver holds.
     *
     * @return `true` when the template is applied or its application could not be disproved,
     *   `false` only when the apiserver confirmed the patch was rejected
     */
    @Suppress("TooGenericExceptionCaught") // an apply failure must not keep the workspace down
    private suspend fun applyTemplate(
        identity: WorkspaceIdentity,
        template: Map<String, Any?>
    ): Boolean {
        repeat(TEMPLATE_APPLY_ATTEMPTS) { attempt ->
            val outcome = attemptTemplateApplication(identity, template)
            if (outcome != TemplateApplication.AMBIGUOUS) {
                return outcome == TemplateApplication.APPLIED
            }
            if (attempt < TEMPLATE_APPLY_ATTEMPTS - 1) {
                @Suppress("ConvertLongToDuration")
                delay(REQUEST_READ_RETRY_DELAY)
            }
        }
        thisLogger().warn(
            "Could not establish whether the devfile template was applied to $identity; " +
                "not reporting a failure and starting the workspace with the current template"
        )
        return true
    }

    /**
     * Runs one idempotent replace and classifies its outcome. Cancellation is rethrown so it keeps
     * propagating; any other failure is only reported as unapplied when the apiserver rejected it.
     */
    @Suppress("TooGenericExceptionCaught") // an apply failure must not keep the workspace down
    private fun attemptTemplateApplication(
        identity: WorkspaceIdentity,
        template: Map<String, Any?>
    ): TemplateApplication {
        return try {
            workspaces.setSpecTemplate(identity.namespace, identity.name, template)
            thisLogger().debug("Applied devfile template to $identity")
            TemplateApplication.APPLIED
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            thisLogger().warn("Could not apply devfile template to $identity", e)
            classifyTemplateFailure(e)
        }
    }

    /**
     * Distinguishes an apiserver rejection, which confirms the patch was not committed, from an
     * ambiguous failure such as a lost or timed-out response.
     */
    private fun classifyTemplateFailure(e: Exception): TemplateApplication {
        val apiException = e as? ApiException ?: return TemplateApplication.AMBIGUOUS
        return when {
            apiException.isNoHttpStatus()
                    || apiException.isRequestTimeout()
                    || apiException.isRetryable() ->
                TemplateApplication.AMBIGUOUS

            else ->
                TemplateApplication.NOT_APPLIED
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

    @Suppress("UnstableApiUsage", "MagicNumber") // cleanup/poll delays are explicit here
    private suspend fun close(thinClient: ThinClientHandle) {
        thisLogger().debug("Closing thin client for $namespace/$workspaceName")
        thinClient.close()
        @Suppress("ConvertLongToDuration")
        delay(1000L) // Give time for port forwarder cleanup
    }

    private fun stopWorkspaceAndWait(identity: WorkspaceIdentity) {
        thisLogger().debug("Stopping workspace and waiting...")
        workspaces.stopAndWait(
            identity.namespace,
            identity.name,
            DevWorkspaces.RUNNING_TIMEOUT
        )

        thisLogger().debug("Workspace stopped")
    }

    private fun startWorkspaceAndWait(identity: WorkspaceIdentity) {
        thisLogger().debug("Starting workspace and waiting...")
        workspaces.startAndWait(
            identity.namespace,
            identity.name
        )
        thisLogger().debug("Workspace started and running")
    }

    private suspend fun waitForPodsDeleted(
        identity: WorkspaceIdentity,
        indicator: ProgressIndicator?
    ) {
        val labelSelector = "${DevWorkspacePods.WORKSPACE_LABEL_KEY}=${identity.name}"

        try {
            @Suppress("ConvertLongToDuration", "MagicNumber") // poll/timeout delays are explicit here
            withTimeout(TIMEOUT_SECONDS_DELETE_PODS * 1000L) {
                while (true) {
                    indicator?.checkCanceled()
                    val pods = fetchPodsWithRetry(identity, labelSelector)
                    if (pods.isEmpty()) break
                    delay(2000L)
                }
            }
        } catch (e: TimeoutCancellationException) {
            val remainingPods = getRemainingPodNames(identity, labelSelector)
            throw IllegalStateException("Timeout waiting for pods deletion. Remaining pods: $remainingPods", e)
        }
    }

    @Suppress("MagicNumber") // retry delay is explicit here
    private suspend fun fetchPodsWithRetry(
        identity: WorkspaceIdentity,
        labelSelector: String
    ): List<V1Pod> {
        return try {
            pods.list(identity.namespace, labelSelector).items
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            thisLogger().warn("Failed to list pods, retrying...", e)
            @Suppress("ConvertLongToDuration")
            delay(1000L)
            fetchPodsWithRetry(identity, labelSelector)
        }
    }

    private suspend fun getRemainingPodNames(
        identity: WorkspaceIdentity,
        labelSelector: String
    ): List<String?> {
        return try {
            pods.list(identity.namespace, labelSelector).items.map { it.metadata?.name }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun removeAnnotations() {
        runCatching {
            workspaces.removeRestartRequest(namespace, workspaceName)
            thisLogger().debug("Removed restart request annotations from $namespace/$workspaceName")
        }.onFailure { e ->
            thisLogger().debug("Failed to remove restart request annotations from $namespace/$workspaceName", e)
        }
    }
}
