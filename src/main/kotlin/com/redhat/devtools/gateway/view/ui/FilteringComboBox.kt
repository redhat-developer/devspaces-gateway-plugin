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

import com.intellij.openapi.util.Key
import java.awt.event.ActionListener
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.ItemEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.text.JTextComponent

object FilteringComboBox {

    private val popupOpened = PopupOpened()

    fun <T> create(
        toString: (T?) -> String = { it.toString() },
        toElement: (String) -> T?
    ): JComboBox<T> {
        val comboBox = JComboBox<T>()
        comboBox.isEditable = true
        val editor = UnsettableComboBoxEditor(comboBox, toString, toElement)
        comboBox.editor = editor
        popupOpened.reset(comboBox)

        comboBox.model = FilteringComboBoxModel<T>()
        comboBox.setRenderer(onListItemRendered<T>(toString))

        comboBox.addPopupMenuListener(onPopupVisible(comboBox, toString))

        val editorComponent = editor.editorComponent
        editorComponent.addKeyListener(onKeyPressed(editorComponent, comboBox, toString))
        comboBox.addItemListener(onItemSelected(editorComponent, toString))
        return comboBox
    }

    private fun <T> onPopupVisible(
        comboBox: JComboBox<T>,
        toString: (T) -> String
    ): PopupMenuListener = object : PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
            val allItems = comboBox.filteringModel().getAllItems()
            val editorText = getEditorComponent(comboBox)?.text ?: ""
            val visible = if (popupOpened.isProgrammatic(comboBox)) {
                filterItems(editorText, allItems, toString)
            } else {
                allItems
            }
            comboBox.filteringModel().showOnly(visible)
            comboBox.selectedIndex = -1 // prevent item selection
            popupOpened.reset(comboBox)
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
        toString: (T?) -> String
    ): (ItemEvent) -> Unit = { event ->
        if (editorComponent != null
            && event.stateChange == ItemEvent.SELECTED) {
            (event.item as? T)?.let { selectedItem ->
                editorComponent.text = toString(selectedItem)
                editorComponent.caret.isSelectionVisible = true // allow selectAll() with no focus
                editorComponent.selectAll()
            }
        }
    }

    private fun <T> onKeyPressed(
        editor: JTextField,
        comboBox: JComboBox<T>,
        toString: (T) -> String
    ): KeyAdapter = object : KeyAdapter() {
        override fun keyReleased(e: KeyEvent) {
            if (isNavigationKey(e)) {
                return
            }
            val currentText = editor.text
            val filtered = filterItems(currentText, comboBox.filteringModel().getAllItems(), toString)
            showPopup(comboBox, filtered)
        }
    }

    private fun <T> showPopup(
        comboBox: JComboBox<T>,
        items: List<T>
    ) {
        popupOpened.setProgrammatic(true, comboBox)
        val editor = getEditorComponent(comboBox)
        val selection = Selection(comboBox.editor.editorComponent as? JTextComponent).backup()

        val currentTextInEditor = editor?.text ?: ""
        comboBox.filteringModel().showOnly(items)

        editor?.text = currentTextInEditor

        // Restore the selection after the model and text are set
        selection.restore()
        comboBox.selectedIndex = -1 // prevent pre-selection

        if (items.isNotEmpty() && !comboBox.isPopupVisible) {
            comboBox.showPopup()
        } else if (items.isEmpty() && comboBox.isPopupVisible) {
            comboBox.hidePopup()
        }
        popupOpened.reset(comboBox)
    }

    private fun <T> filterItems(
        currentText: String,
        allItems: List<T>,
        toString: (T) -> String
    ): List<T> {
        val lowerText = currentText.lowercase()
        val filtered = allItems.filter { toString(it).lowercase().contains(lowerText) }

        return filtered.sortedWith(
            compareBy<T> { toString(it).lowercase().indexOf(lowerText) }
                .thenBy { toString(it).lowercase() }
        )
    }

    private fun <T> JComboBox<T>.filteringModel(): FilteringComboBoxModel<T> {
        return model as FilteringComboBoxModel<T>
    }

    private class UnsettableComboBoxEditor<T>(
        private val comboBox: JComboBox<T>,
        private val toString: (T?) -> String,
        private val toElement: (String) -> T?
    ) : ComboBoxEditor {
        private val textField = JTextField()

        override fun getEditorComponent(): JTextField = textField

        override fun setItem(anObject: Any?) {
            /*
            * Noop impl on purpose:
            * Called by JComboBox to update the editor.
            * Don't replace editor content when the popup is shown. Only when user actively selects an item.
            */
        }

        override fun getItem(): T? {
            val text = textField.text
            val allItems = comboBox.filteringModel().getAllItems()

            /*
             * item =
             * 1. selected item that is matching text field OR
             * 2. item present in (combobox-) list if it's matching text field OR
             * 3. create an new item with content of text field OR
             * 4. null
             */
            return (comboBox.selectedItem as? T)?.takeIf { toString(it) == text } // selected item = item not present in list
                ?: allItems.find { toString(it) == text } // item present in list
                ?: toElement(text) // no new item, not item in list -> create a new item
        }

        override fun selectAll() {
            textField.selectAll()
        }

        override fun addActionListener(listener: ActionListener?) {
            textField.addActionListener(listener)
        }

        override fun removeActionListener(listener: ActionListener?) {
            textField.removeActionListener(listener)
        }
    }

    private fun <T> getEditorComponent(comboBox: JComboBox<T>): JTextField? {
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

    private class Selection(private val component: JTextComponent?) {

        private var selectionStart = -1
        private var selectionEnd = -1

        fun backup(): Selection {
            if (component == null) {
                return this
            }
            selectionStart = component.selectionStart
            selectionEnd = component.selectionEnd
            return this
        }

        fun restore() {
            if (component != null
                && selectionStart != -1
                && selectionEnd != -1) {
                component.select(selectionStart, selectionEnd)
            }
        }
    }

    private class PopupOpened {

        private val key = Key.create<Boolean>("isPopupProgrammatic")

        fun isProgrammatic(component: JComponent): Boolean {
            val property = component.getClientProperty(key)
            return property as? Boolean ?: false
        }

        fun setProgrammatic(value: Boolean, component: JComponent) {
            component.putClientProperty(key, value)
        }

        fun reset(component: JComponent) {
            setProgrammatic(false, component)
        }
    }

    private class FilteringComboBoxModel<T>() : DefaultComboBoxModel<T>() {

        private val hidden = mutableSetOf<T>()

        override fun getIndexOf(item: Any?): Int {
            return getAllVisible().indexOf(item)
        }

        override fun getSize(): Int {
            return getAllItems()
                .filter { it !in hidden }.size
        }

        override fun getElementAt(index: Int): T? {
            return getAllVisible()
                .elementAtOrNull(index)
        }

        override fun removeAllElements() {
            hidden.clear()
            super.removeAllElements()
        }

        fun showOnly(items: List<T>) {
            val toHide = getAllItems().filter { !items.contains(it) }
            hidden.clear()
            hidden.addAll(toHide)
            fireContentsChanged(this, 0, size - 1)
        }

        private fun getAllVisible(): List<T> {
            return getAllItems().filter { it !in hidden }
        }

        fun getAllItems(): List<T> {
            return (0 until super.size)
                .map { super.getElementAt(it) }
        }
    }
}

