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

import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

object FilteringComboBox {

    fun create(allItems: List<String>, onItemUpdated: (String) -> Unit): JComboBox<String> {
        val comboBox = JComboBox<String>()
        comboBox.isEditable = true
        val editor = getEditor(comboBox)
        comboBox.model = DefaultComboBoxModel(allItems.toTypedArray())

        // Key listener for dynamic filtering
        editor?.addKeyListener(
            onKeyPressed(comboBox, onItemUpdated, allItems)
        )

        // Action listener for selection from dropdown
        comboBox.addActionListener {
            onItemSelected(comboBox, onItemUpdated)
        }

        // Show all items when dropdown arrow is clicked
        comboBox.addPopupMenuListener(
            onPopupMenu(comboBox, allItems)
        )

        return comboBox
    }

    private fun onPopupMenu(comboBox: JComboBox<String>, allItems: List<String>): PopupMenuListener = object :
        PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
            SwingUtilities.invokeLater {
                val comboEditor = getEditor(comboBox)
                val currentText = comboEditor?.text.orEmpty()

                val model = comboBox.model as DefaultComboBoxModel<String>
                model.removeAllElements()
                allItems.forEach { model.addElement(it) }

                // keep current text if user typed something
                if (currentText.isNotEmpty()) {
                    comboBox.selectedItem = currentText
                    comboEditor?.text = currentText
                } else {
                    comboBox.selectedItem = null
                }
            }
        }

        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
        override fun popupMenuCanceled(e: PopupMenuEvent?) {}
    }

    private fun onItemSelected(comboBox: JComboBox<String>, onServerUpdated: (String) -> Unit) {
        val selectedServer = comboBox.selectedItem?.toString() ?: return
        onServerUpdated.invoke(selectedServer)
    }

    private fun onKeyPressed(comboBox: JComboBox<String>, onServerUpdated: (String) -> Unit, allItems: List<String>): KeyAdapter {
        return object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent?) {
                if (e == null || isNavigationKey(e)) {
                    return
                }

                val text = getEditor(comboBox)?.text.orEmpty()
                onServerUpdated.invoke(text)
                filterComboBox(text, comboBox, allItems)
            }
        }
    }

    private fun filterComboBox(currentText: String, comboBox: JComboBox<String>, allItems: List<String>) {
        val lowerText = currentText.lowercase()
        val filtered = allItems.filter { it.lowercase().contains(lowerText) }

        // Sort by index of match (earlier = higher priority), then alphabetically
        val sorted = filtered.sortedWith(
            compareBy<String> { it.lowercase().indexOf(lowerText) }
                .thenBy { it.lowercase() }
        )

        val editor = (getEditor(comboBox))
        val caret = editor?.caretPosition ?: 0

        SwingUtilities.invokeLater {
            val model = DefaultComboBoxModel(sorted.toTypedArray())
            comboBox.model = model
            editor?.text = currentText
            editor?.caretPosition = caret
            if (sorted.isNotEmpty())comboBox.showPopup()
        }
    }

    private fun getEditor(comboBox: JComboBox<String>): JTextField? {
        return comboBox.editor.editorComponent as? JTextField
    }

    private fun isNavigationKey(e: KeyEvent): Boolean {
        return when(e.keyCode) {
            KeyEvent.VK_ESCAPE,
            KeyEvent.VK_ENTER,
            KeyEvent.VK_SHIFT,
            KeyEvent.VK_ALT,
            KeyEvent.VK_CONTROL,
            KeyEvent.VK_TAB,
            KeyEvent.VK_UP,
            KeyEvent.VK_DOWN,
            KeyEvent.VK_LEFT,
            KeyEvent.VK_RIGHT ->
                true
            else ->
                false
        }
    }
}