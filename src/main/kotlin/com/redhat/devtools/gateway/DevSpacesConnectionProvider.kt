/*
 * Copyright (c) 2024 Red Hat, Inc.
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
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.Align.Companion.CENTER
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.gateway.api.ConnectionRequestor
import com.jetbrains.gateway.api.GatewayConnectionHandle
import com.jetbrains.gateway.api.GatewayConnectionProvider
import com.redhat.devtools.gateway.openshift.DevWorkspaces
import com.redhat.devtools.gateway.openshift.OpenShiftClientFactory
import com.redhat.devtools.gateway.openshift.kube.KubeConfigBuilder
import io.kubernetes.client.openapi.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JComponent

private const val DW_NAMESPACE = "dwNamespace"
private const val DW_NAME = "dwName"

/**
 * Handles links as:
 *      jetbrains-gateway://connect#type=devspaces
 *      https://code-with-me.jetbrains.com/remoteDev#type=devspaces
 */
class DevSpacesConnectionProvider : GatewayConnectionProvider {

    override suspend fun connect(
        parameters: Map<String, String>,
        requestor: ConnectionRequestor
    ): GatewayConnectionHandle? {
        try {
            thisLogger().debug("Launched Dev Spaces connection provider", parameters)

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
            ctx.client = OpenShiftClientFactory().create()
            ctx.devWorkspace = DevWorkspaces(ctx.client).get(dwNamespace, dwName)

            val thinClient = DevSpacesConnection(ctx)
                .connect({}, {}, {})

            return DevSpacesConnectionHandle(
                thinClient.lifetime,
                thinClient,
                { createComponent(dwName) },
                dwName
            )
        } catch (err: ApiException) {
            if (handleUnauthorizedError(err)) return null
            if (handleNotFoundError(err)) return null

            throw err // Re-throw other errors
        }
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
        if (err.code != 401) return false

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
        if (err.code != 404) return false

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
