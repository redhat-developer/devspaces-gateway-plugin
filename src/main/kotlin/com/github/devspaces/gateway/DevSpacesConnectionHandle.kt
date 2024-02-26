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

import com.jetbrains.gateway.api.CustomConnectionFrameComponentProvider
import com.jetbrains.gateway.api.CustomConnectionFrameContext
import com.jetbrains.gateway.api.GatewayConnectionHandle
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import javax.swing.JComponent

@Suppress("UnstableApiUsage")
class DevSpacesConnectionHandle(
    lifetime: Lifetime,
    clientHandle: ThinClientHandle,
    private val connectionFrameComponent: JComponent,
    private val wsName: String
) : GatewayConnectionHandle(lifetime, clientHandle) {
    override fun customComponentProvider(lifetime: Lifetime) = object : CustomConnectionFrameComponentProvider {
        override val closeConfirmationText = "Disconnect from DevWorkspace ${wsName}?"
        override fun createComponent(context: CustomConnectionFrameContext) = connectionFrameComponent
    }

    override fun getTitle(): String {
        return "DevWorkspace $wsName"
    }

    override fun hideToTrayOnStart(): Boolean {
        return true
    }
}
