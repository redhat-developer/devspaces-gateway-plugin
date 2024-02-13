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

import com.jetbrains.gateway.api.ConnectionRequestor
import com.jetbrains.gateway.api.GatewayConnectionHandle
import com.jetbrains.gateway.api.GatewayConnectionProvider
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.rd.util.lifetime.Lifetime
import java.net.URI

/**
 * Handles links as:
 *      jetbrains-gateway://connect#type=devspaces
 *      https://code-with-me.jetbrains.com/remoteDev#type=devspaces
 */
class DevSpacesConnectionProvider : GatewayConnectionProvider {

    override suspend fun connect(parameters: Map<String, String>, requestor: ConnectionRequestor): GatewayConnectionHandle? {
        val joinLink = parameters["link"]?.replace("_", "&")
        LinkedClientManager.getInstance().startNewClient(Lifetime.Eternal, URI(joinLink))
        return null
    }

    override fun isApplicable(parameters: Map<String, String>): Boolean {
        return parameters["type"] == "devspaces"
    }
}
