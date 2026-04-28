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
package com.redhat.devtools.gateway.view.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * Creates a collapsible section with a clickable label toggle.
 *
 * @param label The text label for the collapsible section
 * @param initiallyExpanded Whether the section starts expanded (default: false)
 * @param content Lambda that builds the collapsible content using Panel DSL
 */
fun Panel.collapsible(
    label: String,
    initiallyExpanded: Boolean = false,
    content: Panel.() -> Unit
) {
    var expanded = initiallyExpanded
    lateinit var toggleLabel: JBLabel
    lateinit var contentPanel: Panel

    row {
        toggleLabel = JBLabel((if (expanded) "▾ " else "▸ ") + label).apply {
            foreground = UIUtil.getLabelForeground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    expanded = !expanded
                    text = (if (expanded) "▾" else "▸") + " $label"
                    contentPanel.visible(expanded)
                }
            })
        }

        cell(toggleLabel)
    }

    contentPanel = panel {
        content()
    }.visible(initiallyExpanded)
}
