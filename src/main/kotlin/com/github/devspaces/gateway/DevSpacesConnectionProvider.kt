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
package com.github.devspaces.gateway

import com.github.devspaces.gateway.openshift.DevWorkspaces
import com.github.devspaces.gateway.openshift.OpenShiftClientFactory
import com.github.devspaces.gateway.openshift.Utils
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.dsl.builder.Align.Companion.CENTER
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.gateway.api.ConnectionRequestor
import com.jetbrains.gateway.api.GatewayConnectionHandle
import com.jetbrains.gateway.api.GatewayConnectionProvider
import com.jetbrains.rd.util.lifetime.Lifetime

private const val DW_NAMESPACE = "dwNamespace"
private const val DW_NAME = "dwName"

/**
 * Handles links as:
 *      jetbrains-gateway://connect#type=devspaces
 *      https://code-with-me.jetbrains.com/remoteDev#type=devspaces
 */
class DevSpacesConnectionProvider : GatewayConnectionProvider {

    @Suppress("UnstableApiUsage")
    override suspend fun connect(parameters: Map<String, String>, requestor: ConnectionRequestor): GatewayConnectionHandle? {
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

        // TODO: probably, we don't need to specify `dwNamespace` here
        //       as `ctx.client` should know it from the local `.kube/config`
        val devWorkspaces = DevWorkspaces(ctx.client).list(dwNamespace) as Map<*, *>
        val devWorkspaceItems = devWorkspaces["items"] as List<*>
        val list = devWorkspaceItems.filter { (Utils.getValue(it, arrayOf("metadata", "name")) as String) == dwName }
        ctx.devWorkspace = list[0]!!

        val thinClient = DevSpacesConnection(ctx).connect()

        val connectionFrameComponent = panel {
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

        return DevSpacesConnectionHandle(Lifetime.Eternal.createNested(), thinClient, connectionFrameComponent, dwName!!)
    }

    override fun isApplicable(parameters: Map<String, String>): Boolean {
        return parameters["type"] == "devspaces"
    }
}
