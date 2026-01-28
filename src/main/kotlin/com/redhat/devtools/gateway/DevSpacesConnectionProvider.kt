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
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import com.redhat.devtools.gateway.devworkspace.DevWorkspaces
import com.redhat.devtools.gateway.openshift.OpenShiftClientFactory
import com.redhat.devtools.gateway.openshift.isNotFound
import com.redhat.devtools.gateway.openshift.isUnauthorized
import com.redhat.devtools.gateway.util.ProgressCountdown
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.util.messageWithoutPrefix
import com.redhat.devtools.gateway.view.SelectClusterDialog
import com.redhat.devtools.gateway.view.ui.Dialogs
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

                        val handle = doConnect(parameters, indicator)
                        val thinClient = handle.clientHandle
                            ?: throw RuntimeException("Failed to obtain ThinClientHandle")

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
                    } catch (e: ApiException) {
                        indicator.text = "Connection failed"
                        runDelayed(2000, { if (indicator.isRunning) indicator.stop() })
                        if (!(handleUnauthorizedError(e) || handleNotFoundError(e))) {
                            Dialogs.error(
                                e.messageWithoutPrefix() ?: "Could not connect to workspace.",
                                "Connection Error"
                            )
                        }

                        if (cont.isActive) cont.resume(null)
                    } catch (e: Exception) {
                        if (e.isCancellationException() || indicator.isCanceled) {
                            indicator.text2 = "Error: ${e.message}"
                            runDelayed(2000) { if (indicator.isRunning) indicator.stop() }
                        } else {
                            runDelayed(2000) { if (indicator.isRunning) indicator.stop() }
                            Dialogs.error(
                                e.message ?: "Could not connect to workspace.",
                                "Connection Error"
                            )
                        }
                        cont.resume(null)
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
        indicator: ProgressCountdown
    ): GatewayConnectionHandle {
        thisLogger().debug("Launched Dev Spaces connection provider", parameters)

        indicator.update(message = "Preparing connection environment…")

        val dwNamespace = parameters[DW_NAMESPACE]
        if (dwNamespace.isNullOrBlank()) {
            thisLogger().error("Query parameter \"$DW_NAMESPACE\" is missing")
            throw IllegalArgumentException("Query parameter \"$DW_NAMESPACE\" is missing")
        }

        val dwName = parameters[DW_NAME]
        if (dwName.isNullOrBlank()) {
            thisLogger().error("Query parameter \"$DW_NAME\" is missing")
            throw IllegalArgumentException("Query parameter \"$DW_NAME\" is missing")
        }
        val ctx = DevSpacesContext()

        indicator.update(message = "Initializing Kubernetes connection…")
        val factory = OpenShiftClientFactory(KubeConfigUtils)
        ctx.client = factory.create()

        indicator.update(message = "Fetching workspace “$dwName” from namespace “$dwNamespace”…")
        ctx.devWorkspace = DevWorkspaces(ctx.client).get(dwNamespace, dwName)

        indicator.update(message = "Connecting to workspace IDE…")
        val thinClient = DevSpacesConnection(ctx)
            .connect({}, {}, {},
                onProgress = { value ->
                    indicator.update(value.title, value.message, value.countdownSeconds)
                },
                checkCancelled = {
                    if (indicator.isCanceled) throw CancellationException("User cancelled the operation")
                }
            )

        indicator.update(message = "Connection established successfully.")
        return DevSpacesConnectionHandle(
            thinClient.lifetime,
            thinClient,
            { createComponent(dwName) },
            dwName)
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

    private fun handleUnauthorizedError(err: ApiException): Boolean {
        if (!err.isUnauthorized()) return false

        Dialogs.error(
            "Your session has expired.\n" +
                    "Please authenticate again to continue.\n\n" +
                    "If you are using token-based authentication, update your token in the kubeconfig file.",
            "Authentication Required"
        )
        return true
    }

    private fun handleNotFoundError(err: ApiException): Boolean {
        if (!err.isNotFound()) return false

        val message = """
            Workspace support not found.
            You're likely connected to a cluster that doesn't have the DevWorkspace Operator installed, or the specified workspace doesn't exist.
        
            Please verify your Kubernetes context, namespace, and that the DevWorkspace Operator is installed and running.
        """.trimIndent()

        Dialogs.error(message, "Resource Not Found")
        return true
    }

    private fun runDelayed(delay: Int, runnable: () -> Unit) {
        Timer(delay) {
            runnable.invoke()
        }.start()
    }
}
