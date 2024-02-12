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

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.Action
import javax.swing.JComponent

class Dialog : DialogWrapper {
    private var text: String
    constructor(title: String, text: String, parent: Component) : super(parent, false) {
        this.text = text

        init()
        setTitle(title)
    }

    override fun createActions(): Array<Action> {
        return arrayOf(this.okAction)
    }

    override fun createCenterPanel(): JComponent {
        val panel = panel {
            row {
                cell(JBTextArea(text)).resizableColumn().align(AlignX.FILL).applyToComponent {
                    foreground = JBUI.CurrentTheme.NotificationInfo.foregroundColor()
                }.component
            }
        }

        return panel
    }
}