/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.view

import com.intellij.openapi.ui.DialogWrapper
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.view.steps.DevSpacesServerStepView
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class SelectClusterDialog(
    ctx: DevSpacesContext
) : DialogWrapper(null, true) {

    private val stepView: DevSpacesServerStepView

    init {
        title = DevSpacesBundle.message("connector.dialog.select_cluster.title")

        stepView = DevSpacesServerStepView(
            devSpacesContext = ctx,
            enableNextButton = { isOKActionEnabled = true },
            triggerNextAction = { doOKAction() }
        )

        isOKActionEnabled = false
        init()
        stepView.onInit()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(stepView.component, BorderLayout.CENTER)
        return panel
    }

    override fun getInitialSize(): Dimension {
        return Dimension(750, 520)
    }

    override fun doOKAction() {
        if (stepView.onNext()) {
            super.doOKAction()
        }
    }

    override fun doCancelAction() {
        stepView.onDispose()
        super.doCancelAction()
    }

    fun showAndConnect(): Boolean {
        return showAndGet()
    }
}