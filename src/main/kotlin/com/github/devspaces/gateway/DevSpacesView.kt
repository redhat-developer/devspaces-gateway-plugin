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

import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.gateway.api.GatewayConnectorView
import com.jetbrains.gateway.api.GatewayUI

class DevSpacesView : GatewayConnectorView {

    override val component = panel {
        row {
            panel {
                separator(WelcomeScreenUIManager.getSeparatorColor())
                indent {
                    row {
                        button("Back") {
                            GatewayUI.getInstance().reset()
                        }
                    }
                }
            }
        }.bottomGap(BottomGap.SMALL)
    }.apply {
        this.background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
    }
}
