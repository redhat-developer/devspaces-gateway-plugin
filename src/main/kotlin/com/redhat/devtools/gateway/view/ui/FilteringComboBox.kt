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

import java.awt.event.ActionListener
import java.awt.event.ItemEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.ComboBoxEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.text.JTextComponent

object FilteringComboBox {

    fun <T> create(
        allItems: List<T>,
        toString: (T) -> String = { it.toString() },
        onItemUpdated: (T) -> Unit
    ): JComboBox<T> {
        val comboBox = JComboBox<T>()
        comboBox.isEditable = true
        comboBox.editor = UnsettableComboBoxEditor()

        val model = DefaultComboBoxModel<T>()
        allItems.forEach { model.addElement(it) }
        comboBox.model = model
        comboBox.setRenderer(onListItemRendered<T>(toString))
        comboBox.addPopupMenuListener(onPopupVisible(allItems, comboBox))

        val editor = getEditor(comboBox)
        editor?.addKeyListener(onKeyPressed(editor, comboBox, allItems, toString))

        comboBox.addItemListener(onItemSelected(editor, onItemUpdated, toString))

        return comboBox
    }

    private fun <T> onPopupVisible(
        allItems: List<T>,
        comboBox: JComboBox<T>
    ): PopupMenuListener = object : PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
            val tempModel = DefaultComboBoxModel<T>().apply {
                allItems.forEach { addElement(it) }
            }
            comboBox.model = tempModel
            comboBox.selectedIndex = -1 // prevent item selection
        }

        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
        override fun popupMenuCanceled(e: PopupMenuEvent?) {}
    }

    private fun <T> onListItemRendered(toString: (T) -> String): ListCellRenderer<T> =
        ListCellRenderer<T> { list, value, index, isSelected, cellHasFocus ->
            val renderer = JLabel()
            renderer.isOpaque = true
            if (value != null) {
                renderer.text = toString(value)
            }
            if (isSelected) {
                renderer.background = list?.selectionBackground ?: UIManager.getColor("ComboBox.selectionBackground")
                renderer.foreground = list?.selectionForeground ?: UIManager.getColor("ComboBox.selectionForeground")
            } else {
                renderer.background = list?.background ?: UIManager.getColor("ComboBox.background")
                renderer.foreground = list?.foreground ?: UIManager.getColor("ComboBox.foreground")
            }
            renderer
        }

    private fun <T> onItemSelected(
        editorComponent: JTextComponent?,
        onItemUpdated: (T) -> Unit,
        toString: (T) -> String
    ): (ItemEvent) -> Unit {
        return { event ->
            // only process if item is actively selected (not when highlighted)
            if (event.stateChange == ItemEvent.SELECTED) {
                val selectedItem = event.item
                if (selectedItem != null) {
                    onItemUpdated.invoke(selectedItem as T)
                    editorComponent?.text = toString(selectedItem) // circumvent ComboboxEditor
                }
            }
        }
    }

    private fun <T> onKeyPressed(
        editor: JTextField,
        comboBox: JComboBox<T>,
        allItems: List<T>,
        toString: (T) -> String
    ): KeyAdapter = object : KeyAdapter() {
        override fun keyReleased(e: KeyEvent) {
            if (isNavigationKey(e)) {
                return
            }
            val currentText = editor.text
            filterComboBox(currentText, comboBox, allItems, toString)
        }
    }

    private fun <T> filterComboBox(
        currentText: String,
        comboBox: JComboBox<T>,
        allItems: List<T>,
        toString: (T) -> String
    ) {
        val lowerText = currentText.lowercase()
        val filtered = allItems.filter { toString(it).lowercase().contains(lowerText) }

        val sorted = filtered.sortedWith(
            compareBy<T> { toString(it).lowercase().indexOf(lowerText) }
                .thenBy { toString(it).lowercase() }
        )

        val editor = getEditor(comboBox)

        SwingUtilities.invokeLater {
            val currentTextInEditor = editor?.text ?: ""
            val model = DefaultComboBoxModel<T>()
            sorted.forEach { model.addElement(it) }

            comboBox.model = model
            editor?.text = currentTextInEditor

            comboBox.selectedIndex = -1 // prevent pre-selection

            if (sorted.isNotEmpty()) {
                if (!comboBox.isPopupVisible) {
                    comboBox.showPopup()
                }
            } else {
                comboBox.hidePopup()
                comboBox.isPopupVisible = false
            }
        }
    }

    private class UnsettableComboBoxEditor() : ComboBoxEditor {
        private val textField = JTextField()

        override fun getEditorComponent(): JTextField = textField

        override fun setItem(anObject: Any?) {
            /*
            * Noop impl on purpose:
            * Called by JComboBox to update the editor.
            * Don't replace editor content when the popup is shown. Only when user actively selects an item.
            */
        }

        override fun getItem(): Any? = textField.text
        override fun selectAll() {}

        override fun addActionListener(l: ActionListener?) {}
        override fun removeActionListener(l: ActionListener?) {}
    }

    private fun <T> getEditor(comboBox: JComboBox<T>): JTextField? {
        return comboBox.editor.editorComponent as? JTextField
    }

    private fun isNavigationKey(e: KeyEvent): Boolean {
        return when(e.keyCode) {
            KeyEvent.VK_ESCAPE,
            KeyEvent.VK_ENTER,
            KeyEvent.VK_TAB,
            KeyEvent.VK_UP,
            KeyEvent.VK_DOWN,
            KeyEvent.VK_LEFT,
            KeyEvent.VK_RIGHT,
            KeyEvent.VK_SHIFT,
            KeyEvent.VK_CONTROL,
            KeyEvent.VK_ALT-> true
            else -> false
        }
    }
}