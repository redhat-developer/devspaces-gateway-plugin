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
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
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

    override fun getInitialSize(): Dimension {
        // Get FontMetrics for the title bar font (uses Label font as a proxy)
        val fontMetrics = JBLabel().getFontMetrics(JBUI.Fonts.label())

        // Measure title string width in pixels
        val titleWidth = fontMetrics.stringWidth(title)

        // Get the preferred height from the center panel
        val contentSize = createCenterPanel().preferredSize

        // Add some padding for window borders and buttons
        val extraWidth = JBUI.scale(100)

        return Dimension(titleWidth + extraWidth, contentSize.height + extraWidth)
    }

}
