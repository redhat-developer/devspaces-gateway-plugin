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
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.ui.dsl.builder.Align.Companion.CENTER
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.gateway.api.ConnectionRequestor
import com.jetbrains.gateway.api.GatewayConnectionHandle
import com.jetbrains.gateway.api.GatewayConnectionProvider
import com.redhat.devtools.gateway.devworkspace.DevWorkspaces
import com.redhat.devtools.gateway.openshift.toWorkspaceException
import com.redhat.devtools.gateway.util.ProgressCountdown
import com.redhat.devtools.gateway.view.SelectClusterDialog
import io.kubernetes.client.openapi.ApiException
import kotlinx.coroutines.*
import java.util.concurrent.CancellationException
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.coroutines.resume

private const val DW_NAMESPACE = "dwNamespace"
private const val DW_NAME = "dwName"

/**
 * Handles links as:
 *      jetbrains-gateway://connect#type=devspaces
 *      https://code-with-me.jetbrains.com/remoteDev#type=devspaces
 */
class DevSpacesConnectionProvider : GatewayConnectionProvider {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("UnstableApiUsage")
    override suspend fun connect(
        parameters: Map<String, String>,
        requestor: ConnectionRequestor
    ): GatewayConnectionHandle? {
        val ctx = DevSpacesContext()

        val confirmed = withContext(Dispatchers.Main) {
            SelectClusterDialog(ctx).showAndConnect()
        }

        if (!confirmed) {
            return null
        }

        return suspendCancellableCoroutine { cont ->
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    val indicator = ProgressCountdown(ProgressManager.getInstance().progressIndicator)
                    try {
                        indicator.isIndeterminate = true
                        indicator.text = "Connecting to DevSpace..."

                        val handle = doConnect(parameters, ctx, indicator)
                        val thinClient = handle.clientHandle
                            ?: throw RuntimeException("Failed to obtain ThinClientHandle")

                        if (thinClient.clientPresent) {
                            indicator.text = "Workspace IDE has started successfully"
                            indicator.text2 = "Opening project window…"
                            runDelayed(1000) { if (indicator.isRunning) indicator.stop() }
                            cont.resume(handle)
                            return@runProcessWithProgressSynchronously
                        }

                        indicator.text = "Waiting for workspace IDE to start..."

                        val ready = CompletableDeferred<GatewayConnectionHandle?>()

                        thinClient.onClientPresenceChanged.advise(thinClient.lifetime,
                            onClientPresenceChanged(ready, indicator, handle)
                        )
                        thinClient.clientFailedToOpenProject.advise(thinClient.lifetime,
                            onClientFailedToOpenProject(ready, indicator)
                        )
                        thinClient.clientClosed.advise(thinClient.lifetime,
                            onClientClosed(ready, indicator)
                        )
                        ready.invokeOnCompletion { error ->
                            if (error == null) {
                                cont.resume(ready.getCompleted())
                            } else {
                                cont.resumeWith(Result.failure(error))
                            }
                        }

                        runBlocking {
                            withTimeoutOrNull(60_000L) { ready.await() } ?: run {
                                if (ready.isActive) {
                                    indicator.text = "Workspace IDE did not report readiness in time."
                                    ready.completeExceptionally(
                                        RuntimeException("Workspace IDE did not report readiness in time.")
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        DevSpacesConnectionProviderErrors.showDialog(e, ctx, indicator)
                        runDelayed(2000) { if (indicator.isRunning) indicator.stop() }
                        if (cont.isActive) cont.resume(null)
                    } finally {
                        indicator.dispose()
                    }
                },
                "Connecting to Workspace IDE...",
                true,
                null
            )
        }
    }

    private fun onClientPresenceChanged(
        ready: CompletableDeferred<GatewayConnectionHandle?>,
        indicator: ProgressIndicator,
        handle: GatewayConnectionHandle
    ): (Unit) -> Unit = {
        ApplicationManager.getApplication().invokeLater {
            if (!ready.isCompleted) {
                indicator.text = "Workspace IDE has started successfully"
                indicator.text2 = "Opening project window…"
                runDelayed(3000) {
                    if (indicator.isRunning) indicator.stop()
                    if (ready.isActive) ready.complete(handle)
                }
            }
        }
    }

    private fun onClientFailedToOpenProject(
        ready: CompletableDeferred<GatewayConnectionHandle?>,
        indicator: ProgressIndicator
    ): (Int) -> Unit = { errorCode ->
        ApplicationManager.getApplication().invokeLater {
            if (!ready.isCompleted) {
                indicator.text = "Failed to open remote project (code: $errorCode)"
                runDelayed(2000) {
                    if (indicator.isRunning) indicator.stop()
                    if (ready.isActive) ready.complete(null)
                }
            }
        }
    }

    private fun onClientClosed(
        ready: CompletableDeferred<GatewayConnectionHandle?>,
        indicator: ProgressIndicator
    ): (Unit) -> Unit = {
        ApplicationManager.getApplication().invokeLater {
            if (!ready.isCompleted) {
                indicator.text = "Workspace IDE closed unexpectedly."
                runDelayed(2000) {
                    if (indicator.isRunning) indicator.stop()
                    if (ready.isActive) ready.complete(null)
                }
            }
        }
    }

    @Suppress("UnstableApiUsage")
    @Throws(IllegalArgumentException::class)
    private fun doConnect(
        parameters: Map<String, String>,
        ctx: DevSpacesContext,
        indicator: ProgressCountdown
    ): GatewayConnectionHandle {
        thisLogger().debug("Launched Dev Spaces connection provider", parameters)

        indicator.update(message = "Preparing connection environment…")

        val dwNamespace = validateDevWorkspaceNamespace(parameters[DW_NAMESPACE])
        val dwName = validateDevWorkspaceName(parameters[DW_NAME])

        if (!ctx.hasClient()) {
            throw IllegalStateException("Cluster dialog completed without authenticating")
        }

        indicator.update(message = "Fetching workspace “$dwName” from namespace “$dwNamespace”…")
        ctx.devWorkspace = fetchDevWorkspace(ctx, dwNamespace, dwName)

        indicator.update(message = "Connecting to workspace IDE…")
        val thinClient = connectToWorkspace(ctx, indicator)

        indicator.update(message = "Connection established successfully.")
        return DevSpacesConnectionHandle(
            thinClient.lifetime,
            thinClient,
            { createComponent(dwName) },
            dwName)
    }

    private fun validateDevWorkspaceName(dwName: String?): String {
        if (dwName.isNullOrBlank()) {
            thisLogger().error("Query parameter \"$DW_NAME\" is missing")
            throw IllegalArgumentException("Query parameter \"$DW_NAME\" is missing")
        }
        return dwName
    }

    private fun validateDevWorkspaceNamespace(dwNamespace: String?): String {
        if (dwNamespace.isNullOrBlank()) {
            thisLogger().error("Query parameter \"$DW_NAMESPACE\" is missing")
            throw IllegalArgumentException("Query parameter \"$DW_NAMESPACE\" is missing")
        }
        return dwNamespace
    }

    override fun isApplicable(parameters: Map<String, String>): Boolean {
        return parameters["type"] == "devspaces"
    }

    private fun createComponent(dwName: String): JComponent {
        return panel {
            indent {
                row {
                    resizableRow()
                    panel {
                        row {
                            icon(DevSpacesIcons.LOGO).align(CENTER)
                        }
                        row {
                            label(dwName).bold().align(CENTER)
                        }
                    }.align(CENTER)
                }
            }
        }
    }

    private fun fetchDevWorkspace(
        ctx: DevSpacesContext,
        namespace: String,
        name: String,
    ) = try {
        DevWorkspaces(ctx.client).get(namespace, name)
    } catch (e: ApiException) {
        throw e.toWorkspaceException(namespace, name, ctx.client.basePath) ?: e
    }

    private fun connectToWorkspace(ctx: DevSpacesContext, indicator: ProgressCountdown) =
        runBlocking(Dispatchers.IO) {
            try {
                DevSpacesConnection(ctx).connect(
                    {}, {}, {},
                    onProgress = { value ->
                        indicator.update(value.title, value.message, value.countdownSeconds)
                    },
                    checkCancelled = {
                        if (indicator.isCanceled) throw CancellationException("User cancelled the operation")
                    },
                    modalityState = indicator.modalityState
                )
            } catch (e: ApiException) {
                throw e.toWorkspaceException(
                    namespace = ctx.devWorkspace.namespace,
                    name = ctx.devWorkspace.name,
                    clusterUrl = ctx.client.basePath,
                ) ?: e
            }
        }

    private fun runDelayed(delay: Int, runnable: () -> Unit) {
        Timer(delay) {
            runnable.invoke()
        }.start()
    }
}
