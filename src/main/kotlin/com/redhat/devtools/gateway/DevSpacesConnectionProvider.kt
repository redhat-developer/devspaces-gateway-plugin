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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.Align.Companion.CENTER
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.gateway.api.ConnectionRequestor
import com.jetbrains.gateway.api.GatewayConnectionHandle
import com.jetbrains.gateway.api.GatewayConnectionProvider
import com.redhat.devtools.gateway.openshift.DevWorkspaces
import com.redhat.devtools.gateway.openshift.OpenShiftClientFactory
import com.redhat.devtools.gateway.openshift.kube.KubeConfigBuilder
import com.redhat.devtools.gateway.openshift.kube.isNotFound
import com.redhat.devtools.gateway.openshift.kube.isUnauthorized
import io.kubernetes.client.openapi.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.border.EmptyBorder

private const val DW_NAMESPACE = "dwNamespace"
private const val DW_NAME = "dwName"

/**
 * Handles links as:
 *      jetbrains-gateway://connect#type=devspaces
 *      https://code-with-me.jetbrains.com/remoteDev#type=devspaces
 */
class DevSpacesConnectionProvider : GatewayConnectionProvider {

    @Suppress("UnstableApiUsage")
    override suspend fun connect(
        parameters: Map<String, String>,
        requestor: ConnectionRequestor
    ): GatewayConnectionHandle? {
        val indicator = StartupProgressIndicator("Connecting to Remote IDE...")
        ApplicationManager.getApplication().invokeAndWait { indicator.show() }

        return withContext(Dispatchers.IO) {
            try {
                indicator.setText("Connecting to DevSpace...")
                indicator.setIndeterminate(true)

                val handle = doConnect(parameters, requestor, indicator) // suspend function assumed

                val thinClient = handle?.clientHandle
                    ?: throw RuntimeException("Failed to obtain ThinClientHandle")

                indicator.setText("Waiting for remote IDE to start...")
                indicator.setText2("Launching environment and initializing IDE window…")

                // Observe signals on thinClient to detect ready/error
                val readyDeferred = CompletableDeferred<GatewayConnectionHandle?>()

                thinClient.onClientPresenceChanged.advise(thinClient.lifetime) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!readyDeferred.isCompleted) {
                            indicator.setText("Remote IDE has started successfully")
                            indicator.setText2("Opening project window…")
                            Timer(3000) {
                                indicator.close(DialogWrapper.OK_EXIT_CODE)
                                readyDeferred.complete(handle)
                            }.start()
                        }
                    }
                }
                thinClient.clientFailedToOpenProject.advise(thinClient.lifetime) { errorCode ->
                    ApplicationManager.getApplication().invokeLater {
                        if (!readyDeferred.isCompleted) {
                            indicator.setText("Failed to open remote project (code: $errorCode)")
                            Timer(2000) {
                                indicator.close(DialogWrapper.CANCEL_EXIT_CODE)
                                readyDeferred.complete(null)
                            }.start()
                        }
                    }
                }
                thinClient.clientClosed.advise(thinClient.lifetime) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!readyDeferred.isCompleted) {
                            indicator.setText("Remote IDE closed unexpectedly.")
                            Timer(2000) {
                                indicator.close(DialogWrapper.CANCEL_EXIT_CODE)
                                readyDeferred.complete(null)
                            }.start()
                        }
                    }
                }

                readyDeferred.await()
            } catch (err: ApiException) {
                indicator.setText("Connection failed")
                Timer(2000) {
                    indicator.close(DialogWrapper.CANCEL_EXIT_CODE)
                }.start()

                if (handleUnauthorizedError(err) || handleNotFoundError(err)) {
                    null
                } else {
                    throw err
                }
            } catch (e: Exception) {
                indicator.setText("Unexpected error: ${e.message}")
                Timer(2000) {
                    indicator.close(DialogWrapper.CANCEL_EXIT_CODE)
                }.start()
                throw e
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    if (indicator.isShowing) indicator.close(DialogWrapper.OK_EXIT_CODE)
                }
            }
        }
    }

    @Suppress("UnstableApiUsage")
    @Throws(IllegalArgumentException::class)
    private fun doConnect(
        parameters: Map<String, String>,
        requestor: ConnectionRequestor,
        indicator: ProgressIndicator? = null
    ): GatewayConnectionHandle? {
        thisLogger().debug("Launched Dev Spaces connection provider", parameters)

        indicator?.text2 = "Preparing connection environment…"

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

        indicator?.text2 = "Initializing Kubernetes connection…"
        val ctx = DevSpacesContext()
        ctx.client = OpenShiftClientFactory().create()

        indicator?.text2 = "Fetching DevWorkspace “$dwName” from namespace “$dwNamespace”…"
        ctx.devWorkspace = DevWorkspaces(ctx.client).get(dwNamespace, dwName)

        indicator?.text2 = "Establishing remote IDE connection…"
        val thinClient = DevSpacesConnection(ctx).connect({}, {}, {})

        indicator?.text2 = "Connection established successfully."
        return DevSpacesConnectionHandle(thinClient.lifetime, thinClient, { createComponent(dwName) }, dwName)
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

    private suspend fun handleUnauthorizedError(err: ApiException): Boolean {
        if (!err.isUnauthorized()) return false

        val tokenNote = if (KubeConfigBuilder.isTokenAuthUsed())
            "\n\nYou are using token-based authentication.\nUpdate your token in the kubeconfig file."
        else ""

        withContext(Dispatchers.Main) {
            Messages.showErrorDialog(
                "Your session has expired.\nPlease log in again to continue.$tokenNote",
                "Authentication Required"
            )
        }
        return true
    }

    private suspend fun handleNotFoundError(err: ApiException): Boolean {
        if (!err.isNotFound()) return false

        val message = """
            Workspace or DevWorkspace support not found.
            You're likely connected to a cluster that doesn't have the DevWorkspace Operator installed, or the specified workspace doesn't exist.
        
            Please verify your Kubernetes context, namespace, and that the DevWorkspace Operator is installed and running.
        """.trimIndent()

        withContext(Dispatchers.Main) {
            Messages.showErrorDialog(message, "Resource Not Found")
        }
        return true
    }
}

// Common progress dialog to monitor the connection and the renote IDE readiness
class StartupProgressIndicator (
    initialTitle: String = "Progress Indicator"
) : DialogWrapper(false), ProgressIndicator {
    private val progressBar = JProgressBar().apply { isIndeterminate = true }
    private val mainTextLabel = JLabel("Initializing...")
    private val subTextLabel = JLabel("")

    @Volatile
    private var canceled = false

    init {
        title = initialTitle
        isModal = false
        isResizable = true
        init()
    }

    fun setIndeterminateValue(indeterminate: Boolean) = ApplicationManager.getApplication().invokeLater {
        progressBar.isIndeterminate = indeterminate
    }

    fun setFractionValue(fraction: Double) = ApplicationManager.getApplication().invokeLater {
        progressBar.isIndeterminate = false
        progressBar.value = (fraction * 100).toInt()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(5, 5)).apply {
        border = EmptyBorder(10, 10, 10, 10)
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(mainTextLabel)
        add(Box.createVerticalStrut(4))
        add(subTextLabel)
        add(Box.createVerticalStrut(8))
        add(progressBar)
    }

    override fun createActions(): Array<Action> = emptyArray()
    override fun getPreferredFocusedComponent() = mainTextLabel
    override fun getPreferredSize(): Dimension = Dimension(400, 120)
    override fun getText(): String = mainTextLabel.text
    override fun getText2(): String = subTextLabel.text

    override fun setText(text: String?) = ApplicationManager.getApplication().invokeLater {
        mainTextLabel.text = shortenMessage(text ?: "")
        subTextLabel.text = ""
    }

    override fun setText2(text: String?) = ApplicationManager.getApplication().invokeLater {
        subTextLabel.text = shortenMessage(text ?: "")
    }

    override fun setIndeterminate(indeterminate: Boolean) = setIndeterminateValue(indeterminate)
    override fun isIndeterminate(): Boolean = progressBar.isIndeterminate

    override fun setFraction(fraction: Double) = setFractionValue(fraction)
    override fun getFraction(): Double = progressBar.value / 100.0

    override fun cancel() {
        canceled = true
        close(CANCEL_EXIT_CODE)
    }

    override fun isCanceled(): Boolean = canceled

    override fun start() {}
    override fun stop() {}
    override fun isRunning(): Boolean = isShowing
    override fun pushState() {}
    override fun popState() {}

    override fun getModalityState(): ModalityState {
        return if (isShowing) ModalityState.current() else ModalityState.nonModal()
    }

    override fun setModalityProgress(progressIndicator: ProgressIndicator?) {}

    override fun checkCanceled() {
        if (isCanceled) throw ProcessCanceledException()
    }

    override fun isPopupWasShown(): Boolean {
        return isShowing
    }

    private fun shortenMessage(message: String, maxLength: Int = 100): String {
        if (message.length <= maxLength) return message

        val head = message.take(maxLength / 2 - 3)
        val tail = message.takeLast(maxLength / 2 - 3)
        return "$head…$tail"
    }
}