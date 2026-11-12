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

import java.awt.Dialog
import java.awt.KeyboardFocusManager
import java.awt.Window
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.RootPaneContainer

/**
 * Finds the component that should own a platform progress dialog.
 *
 * Prefers focus inside Gateway; when the thin client holds OS focus (no active host window),
 * falls back to a showing dialog other than the host IDE root frame.
 */
object ProgressDialogParent {

    fun component(): JComponent? {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (focusOwner is JComponent
            && focusOwner.isShowing) {
            return focusOwner
        }
        return (ownerWindow() as? RootPaneContainer)?.rootPane
    }

    private fun ownerWindow(): Window {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val rootFrame = JOptionPane.getRootFrame()
        return sequenceOf(focusManager.activeWindow, focusManager.focusedWindow)
            .filterNotNull()
            .firstOrNull {
                it.isShowing
                        && it !== rootFrame
            }
            ?: Window.getWindows()
                .lastOrNull {
                    it.isShowing
                            && it !== rootFrame
                            && it is Dialog
                }
            ?: focusManager.activeWindow
            ?: focusManager.focusedWindow
            ?: rootFrame
    }
}
