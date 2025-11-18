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
package com.redhat.devtools.gateway.view.ui

import com.intellij.openapi.application.invokeLater
import com.intellij.ui.AncestorListenerAdapter
import com.redhat.devtools.gateway.openshift.Cluster
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.AncestorEvent

fun <T> JList<T>.onDoubleClick(action: (T) -> Unit) {
    addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (e.clickCount == 2) {
                selectedValue?.let { action(it) }
            }
        }
    })
}

fun <T> JComboBox<T>.getAllElements(): List<T?> {
    return (0 until model.size)
        .map { index -> model.getElementAt(index) }.toList()
}

fun JPanel.requestInitialFocus(component: JComboBox<Cluster>) {
    addAncestorListener(object : AncestorListenerAdapter() {
        override fun ancestorAdded(event: AncestorEvent?) {
            invokeLater {
                component.requestFocusInWindow()
            }
            removeAncestorListener(this)
        }
    })
}
