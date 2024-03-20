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
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.Component
import java.awt.event.ActionListener
import javax.swing.Action
import javax.swing.JComponent

class LoaderDialog(private var text: String, parent: Component) : DialogWrapper(parent, false) {
    init {
        super.init()
        this.setUndecorated(true)
    }

    fun hide() {
        this.close(0)
    }

    override fun createActions(): Array<Action> {
        return arrayOf()
    }

    override fun createCancelAction(): ActionListener? {
        return null
    }

    override fun createCenterPanel(): JComponent {
        return panel { row { label(text).resizableColumn().align(Align.CENTER) } }
    }
}