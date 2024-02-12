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
package com.github.devspaces.gateway.view

import com.github.devspaces.gateway.openshift.DevSpacesContext
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import com.jetbrains.gateway.api.GatewayConnectorView
import javax.swing.JComponent

class DevSpacesMainView : GatewayConnectorView {

    override val component: JComponent
        get() = Wrapper(DevSpacesWizardView(DevSpacesContext())).apply {
            border = JBUI.Borders.empty()
        }
}
