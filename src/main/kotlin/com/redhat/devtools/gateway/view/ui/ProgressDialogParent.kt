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

import com.intellij.openapi.application.ApplicationManager
import java.awt.Dialog
import java.awt.KeyboardFocusManager
import java.awt.Window
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.RootPaneContainer

/**
 * Finds the component that should own a platform progress dialog.
 *
 * Uses the focus owner when it belongs to a showing window, so the dialog follows the window the
 * user is working in; otherwise falls back to a showing dialog that is not the host IDE root frame,
 * and finally to the active or focused window. It cannot tell a Gateway window from a host IDE
 * window, so a host dialog may be chosen when the focus is not in the Gateway.
 *
 * The lookup runs on the EDT, because it reads Swing focus/window state and returns a component.
 */
object ProgressDialogParent {

    fun component(): JComponent? {
        val application = ApplicationManager.getApplication()
        if (application == null || application.isDispatchThread) {
            return findComponent()
        }
        var component: JComponent? = null
        application.invokeAndWait { component = findComponent() }
        return component
    }

    private fun findComponent(): JComponent? {
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
