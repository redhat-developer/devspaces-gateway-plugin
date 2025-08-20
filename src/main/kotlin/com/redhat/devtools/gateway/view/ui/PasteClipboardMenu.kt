/*
 * Copyright (c) 2025 Red Hat, Inc.
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

import javax.swing.JTextField
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

object PasteClipboardMenu {

    fun addTo(widget: JTextField) {
        val popupMenu = javax.swing.JPopupMenu()

        val pasteItem = javax.swing.JMenuItem("Paste")
        pasteItem.addActionListener {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val contents = clipboard.getContents(null)
            if (contents != null && contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                val text = contents.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as String
                widget.replaceSelection(text) // replaces current selection or inserts at caret
            }
        }
        popupMenu.add(pasteItem)

        // Update paste enabled state each time menu opens
        popupMenu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                val contents = clipboard.getContents(null)
                pasteItem.isEnabled = contents != null &&
                        contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)
            }

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
            override fun popupMenuCanceled(e: PopupMenuEvent?) {}
        })

        widget.componentPopupMenu = popupMenu
    }

}