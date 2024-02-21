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
package com.github.devspaces.gateway.view.steps

import com.github.devspaces.gateway.DevSpacesBundle
import com.github.devspaces.gateway.DevSpacesContext
import com.github.devspaces.gateway.openshift.OpenShiftClientFactory
import com.github.devspaces.gateway.openshift.Projects
import com.github.devspaces.gateway.view.Dialog
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI

class DevSpacesOpenShiftConnectionStepView(private var devSpacesContext: DevSpacesContext) : DevSpacesWizardStep {
    private var tfHost = JBTextField("")
    private var tfPort = JBTextField("6443")
    private var tfPassword = JBPasswordField()

    override val nextActionText = DevSpacesBundle.message("connector.wizard_step.openshift_connection.button.next")
    override val previousActionText =
        DevSpacesBundle.message("connector.wizard_step.openshift_connection.button.previous")

    override val component = panel {
        row {
            label(DevSpacesBundle.message("connector.wizard_step.openshift_connection.title")).applyToComponent {
                font = JBFont.h2().asBold()
            }
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.host")) {
            cell(tfHost).align(Align.FILL)
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.port")) {
            cell(tfPort).align(Align.FILL)
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.token")) {
            cell(tfPassword).align(Align.FILL)
        }
    }.apply {
        background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
        border = JBUI.Borders.empty(8)
    }

    override fun onInit() {}

    override fun onPrevious(): Boolean {
        return true
    }

    override fun onNext(): Boolean {
        return testConnection()
    }

    private fun testConnection(): Boolean {
        try {
            val client = OpenShiftClientFactory(tfHost.text, tfPort.text, tfPassword.password).create()
            devSpacesContext.client = client

            Projects(client).list()
            return true
        } catch (e: Exception) {
            val dialog = Dialog(
                "Failed to connect to OpenShift API server",
                String.format("Caused: %s", e.toString()),
                component
            )
            dialog.show()
            return false
        }
    }
}