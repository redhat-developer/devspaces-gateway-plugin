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
package com.redhat.devtools.gateway.view

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.Component
import javax.swing.Action
import javax.swing.JComponent

class InformationDialog(title: String, private var text: String, parent: Component) : DialogWrapper(parent, false) {
    init {
        super.init()
        this.title = title
    }

    override fun createActions(): Array<Action> {
        return arrayOf(this.okAction)
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                cell(JBLabel(text)).resizableColumn().align(AlignX.FILL)
            }
        }
    }
}
