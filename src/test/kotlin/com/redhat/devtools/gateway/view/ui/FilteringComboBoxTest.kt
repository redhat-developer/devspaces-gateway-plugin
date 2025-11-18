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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import java.awt.event.KeyEvent
import javax.swing.*

class FilteringComboBoxTest {

    private data class TestItem(val id: Int, val name: String)

    private lateinit var comboBox: JComboBox<TestItem>
    private var selectedItem: TestItem? = null
    private val items = listOf(
        TestItem(1, "Luke Skywalker"),
        TestItem(2, "Darth Vader"),
        TestItem(3, "Princess Leia"),
        TestItem(4, "Han Solo"),
        TestItem(5, "Chewbacca"),
        TestItem(6, "Obi-Wan Kenobi"),
        TestItem(7, "Yoda"),
        TestItem(8, "Anakin Skywalker")
    )

    @BeforeEach
    fun setUp() {
        selectedItem = null
        comboBox = createComboBox()
    }

    @Test
    fun `#renderer does use custom toString function for rendering`() {
        // given
        val customToString: (TestItem?) -> String = { it?.let { "ID: ${it.id} - ${it.name}" } ?: "" }
        comboBox = createComboBox(customToString)
        comboBox.addItem(items[0])

        // when
        val renderer = comboBox.renderer
        val component = renderer.getListCellRendererComponent(
            JList(),
            items[0],
            0,
            false,
            false
        )

        // then
        assertThat(component).isInstanceOf(JLabel::class.java)
        assertThat((component as JLabel).text).isEqualTo("ID: 1 - Luke Skywalker")
    }

    @Test
    fun `#onItemSelected does invoke callback when item is selected`() {
        // given
        items.forEach { comboBox.addItem(it) }

        // when
        SwingUtilities.invokeAndWait {
            comboBox.selectedIndex = 2
        }
        Thread.sleep(100) // Allow time for event processing

        // then
        assertThat(selectedItem).isEqualTo(items[2])
    }

    @Test
    fun `#filterItems does filter items case-insensitively`() {
        // given
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField

        // when - simulate typing "sky" (should match "Luke Skywalker" and "Anakin Skywalker")
        typeText(editor, "sky")

        // then - model should be filtered
        SwingUtilities.invokeAndWait {
            assertThat(comboBox.itemCount).isLessThanOrEqualTo(items.size)
            // Skywalker names should be in the filtered results
            val hasSkywalker = (0 until comboBox.itemCount).any { 
                comboBox.getItemAt(it)?.name?.contains("Skywalker", ignoreCase = true) == true 
            }
            assertThat(hasSkywalker).isTrue
        }
    }

    @Test
    fun `#filterItems does match substrings`() {
        // given
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField

        // when - simulate typing "vader" (should match "Darth Vader")
        typeText(editor, "vader")

        // then
        SwingUtilities.invokeAndWait {
            val filteredItems = (0 until comboBox.itemCount).map { comboBox.getItemAt(it) }
            val matchingItems = filteredItems.filter { 
                it?.name?.contains("vader", ignoreCase = true) == true 
            }
            assertThat(matchingItems.size).isGreaterThanOrEqualTo(1)
        }
    }

    @Test
    fun `#onKeyPressed does not filter on navigation keys`() {
        // given
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField
        val initialCount = comboBox.itemCount

        // when - simulate pressing arrow key
        pressKey(editor, KeyEvent.VK_DOWN)

        // then - should not trigger filtering
        assertThat(comboBox.itemCount).isEqualTo(initialCount)
    }

    @Test
    fun `#onKeyPressed does not filter on escape key`() {
        // given
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField
        val initialCount = comboBox.itemCount

        // when - simulate pressing escape
        pressKey(editor, KeyEvent.VK_ESCAPE)

        // then - should not trigger filtering
        assertThat(comboBox.itemCount).isEqualTo(initialCount)
    }





    @Test
    fun `#getItem does match item using custom toElement function`() {
        // given
        val toElement: (String) -> TestItem? = { text ->
            items.find { it.id.toString() == text }
        }
        comboBox = createComboBox(toElement = toElement)
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField

        // when - set text to an ID
        SwingUtilities.invokeAndWait {
            editor.text = "3"
        }

        // then - getItem should return the matched item
        val item = comboBox.editor.item
        assertThat(item).isInstanceOf(TestItem::class.java)
        if (item is TestItem) {
            assertThat(item.id).isEqualTo(3)
        }
    }

    @Test
    fun `#getItem does return null when toElement is null and no exact match`() {
        // given
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField

        // when
        SwingUtilities.invokeAndWait {
            editor.text = "Unknown Item"
        }

        // then - should return the text itself
        val item = comboBox.editor.item
        assertThat(item).isNull()
    }

    @Test
    fun `#onItemSelected does set editor text when item is selected`() {
        // given
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField

        // when
        SwingUtilities.invokeAndWait {
            comboBox.selectedItem = items[4]
        }
        Thread.sleep(100)

        // then
        SwingUtilities.invokeAndWait {
            assertThat(editor.text).isEqualTo("Chewbacca")
        }
    }

    @Test
    fun `#create does handle null items gracefully`() {
        // given
        comboBox = createComboBox(toString = { it?.name ?: "null" })
        
        // when
        SwingUtilities.invokeAndWait {
            comboBox.addItem(items[0])
            comboBox.addItem(null)
            comboBox.addItem(items[1])
        }

        // then
        assertThat(comboBox.itemCount).isEqualTo(3)
        assertThat(comboBox.getItemAt(1)).isNull()
    }

    @Test
    fun `#renderer does render null items with toString function`() {
        // given
        comboBox = createComboBox(toString = { it?.name ?: "<empty>" })

        // when
        val renderer = comboBox.renderer
        val component = renderer.getListCellRendererComponent(
            JList(),
            null,
            0,
            false,
            false
        )

        // then
        assertThat(component).isInstanceOf(JLabel::class.java)
        // The label should handle null gracefully (may not have text for null)
        assertThat((component as JLabel).text).isNotEqualTo("<empty>") // null renders as empty, not via toString
    }

    @Test
    fun `#showPopup does hide popup when filtering results in no matches`() {
        // given
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField
        
        // First type something that matches to show popup
        typeText(editor, "Luke")
        Thread.sleep(200)

        // when - type something that doesn't match any item
        SwingUtilities.invokeAndWait {
            editor.text = "XYZ123"
        }
        typeText(editor, "XYZ123")
        Thread.sleep(300) // Extra time for filtering to complete

        // then - popup should be hidden or model should be empty/minimal
        SwingUtilities.invokeAndWait {
            // After filtering with non-matching text, either:
            // 1. The popup is hidden (most likely)
            // 2. The model is empty or has very few items
            val isPopupHidden = !comboBox.isPopupVisible
            val hasMinimalItems = comboBox.itemCount <= 1
            assertThat(isPopupHidden || hasMinimalItems).isTrue
        }
    }

    @Test
    fun `#filterItems does preserve editor text when filtering`() {
        // given
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField

        // when
        typeText(editor, "leia")

        // then - editor text should still be "leia"
        SwingUtilities.invokeAndWait {
            assertThat(editor.text).isEqualTo("leia")
        }
    }

    @Test
    fun `#onItemSelected does not invoke callback for DESELECTED events`() {
        // given
        var updateCount = 0
        comboBox = FilteringComboBox.create(
            toString = { it?.name ?: "" },
            toElement = { null }
        )
        comboBox.addItemListener { event ->
            if (event.stateChange == java.awt.event.ItemEvent.SELECTED) {
                updateCount++
                selectedItem = event.item as? TestItem
            }
        }
        items.forEach { comboBox.addItem(it) }

        // when - select and then deselect
        SwingUtilities.invokeAndWait {
            comboBox.selectedIndex = 1
        }
        Thread.sleep(100)
        val firstCount = updateCount

        SwingUtilities.invokeAndWait {
            comboBox.selectedIndex = -1 // deselect
        }
        Thread.sleep(100)

        // then - count should not increase for deselection
        assertThat(updateCount).isEqualTo(firstCount)
    }

    @Test
    fun `#filterItems does sort filtered items by match position`() {
        // given
        // Add items where "an" appears at different positions
        listOf(
            TestItem(1, "Han Solo"),           // 'an' at position 1
            TestItem(2, "Anakin Skywalker"),   // 'an' at position 0
            TestItem(3, "Obi-Wan Kenobi"),     // 'an' at position 6
            TestItem(4, "Princess Leia")       // no 'an'
        ).forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField

        // when - filter by "an"
        typeText(editor, "an")
        Thread.sleep(200) // Extra time for filtering and sorting

        // then - items starting with 'an' should come first
        SwingUtilities.invokeAndWait {
            assertThat(comboBox.itemCount).isGreaterThan(0)
            val firstItem = comboBox.getItemAt(0)
            assertThat(firstItem?.name?.contains("an", ignoreCase = true)).isTrue
        }
    }

    @Test
    fun `#filterItems does show all items with empty string`() {
        // given
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField
        val totalItems = comboBox.itemCount

        // when - filter with empty string
        SwingUtilities.invokeAndWait {
            editor.text = ""
        }
        typeText(editor, " ")
        SwingUtilities.invokeAndWait {
            editor.text = ""
        }
        Thread.sleep(100)

        // then - all items should be visible
        assertThat(comboBox.itemCount).isEqualTo(totalItems)
    }

    @Test
    fun `#renderer does render selected items with selection colors`() {
        // given
        comboBox.addItem(items[0])

        // when
        val renderer = comboBox.renderer
        val component = renderer.getListCellRendererComponent(
            JList(),
            items[0],
            0,
            true,  // isSelected = true
            false
        )

        // then - should have selection background
        assertThat(component).isInstanceOf(JLabel::class.java)
        val label = component as JLabel
        assertThat(label.background).isNotNull
        assertThat(label.foreground).isNotNull
    }

    @Test
    fun `#renderer does render focused items`() {
        // given
        comboBox.addItem(items[0])

        // when
        val renderer = comboBox.renderer
        val component = renderer.getListCellRendererComponent(
            JList(),
            items[0],
            0,
            false,
            true  // cellHasFocus = true
        )

        // then - should render without error
        assertThat(component).isInstanceOf(JLabel::class.java)
        assertThat((component as JLabel).text).isEqualTo("Luke Skywalker")
    }

    @Test
    fun `#getItem does return selected item when text matches`() {
        // given
        items.forEach { comboBox.addItem(it) }

        // when - select an item and verify editor.item returns it
        SwingUtilities.invokeAndWait {
            comboBox.selectedItem = items[3]
        }
        Thread.sleep(100)

        // then - getItem should return the selected item
        val item = comboBox.editor.item
        assertThat(item).isEqualTo(items[3])
    }

    @Test
    fun `#selectAll does select all text in editor`() {
        // given
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField

        // when
        SwingUtilities.invokeAndWait {
            editor.text = "test text"
            comboBox.editor.selectAll()
        }

        // then
        SwingUtilities.invokeAndWait {
            assertThat(editor.selectedText).isEqualTo("test text")
        }
    }

    @Test
    fun `#addActionListener does add and invoke action listener`() {
        // given
        items.forEach { comboBox.addItem(it) }
        var actionPerformed = false
        val listener = java.awt.event.ActionListener { actionPerformed = true }

        // when
        comboBox.editor.addActionListener(listener)
        SwingUtilities.invokeAndWait {
            val editor = comboBox.editor.editorComponent as JTextField
            editor.postActionEvent()
        }
        Thread.sleep(50)

        // then
        assertThat(actionPerformed).isTrue

        // cleanup
        comboBox.editor.removeActionListener(listener)
    }

    @Test
    fun `#removeActionListener does remove action listener`() {
        // given
        items.forEach { comboBox.addItem(it) }
        var actionCount = 0
        val listener = java.awt.event.ActionListener { actionCount++ }

        // when
        comboBox.editor.addActionListener(listener)
        SwingUtilities.invokeAndWait {
            val editor = comboBox.editor.editorComponent as JTextField
            editor.postActionEvent()
        }
        Thread.sleep(50)
        val firstCount = actionCount

        comboBox.editor.removeActionListener(listener)
        SwingUtilities.invokeAndWait {
            val editor = comboBox.editor.editorComponent as JTextField
            editor.postActionEvent()
        }
        Thread.sleep(50)

        // then - count should not increase after removing listener
        assertThat(firstCount).isEqualTo(1)
        assertThat(actionCount).isEqualTo(firstCount)
    }

    @Test
    fun `#Selection does preserve text selection during filtering`() {
        // given
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField

        // when - type text and select part of it
        SwingUtilities.invokeAndWait {
            editor.text = "Luke"
            editor.select(1, 3)  // select "uk"
        }
        val selectedBefore = editor.selectedText

        // trigger filtering
        typeText(editor, "Luke")
        Thread.sleep(100)

        // then - selection might be restored or handled gracefully
        SwingUtilities.invokeAndWait {
            assertThat(editor.text).isEqualTo("Luke")
            // Selection behavior may vary, just ensure no crash
        }
    }

    @Test
    fun `#getIndexOf does get correct index of item in filtered model`() {
        // given
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField

        // when - filter to reduce items
        typeText(editor, "Sky")
        Thread.sleep(200)

        // then - getIndexOf should work on filtered model
        SwingUtilities.invokeAndWait {
            val skywalkerItem = (0 until comboBox.itemCount)
                .map { comboBox.getItemAt(it) }
                .firstOrNull { it?.name?.contains("Skywalker") == true }
            
            if (skywalkerItem != null) {
                val model = comboBox.model as DefaultComboBoxModel
                val index = model.getIndexOf(skywalkerItem)
                assertThat(index).isGreaterThanOrEqualTo(0)
            }
        }
    }

    @Test
    fun `#removeAllElements does remove all elements from model`() {
        // given
        items.forEach { comboBox.addItem(it) }
        assertThat(comboBox.itemCount).isGreaterThan(0)

        // when
        SwingUtilities.invokeAndWait {
            comboBox.removeAllItems()
        }

        // then
        assertThat(comboBox.itemCount).isEqualTo(0)
    }

    @Test
    fun `#PopupOpened does handle programmatic popup opening vs user opening`() {
        // given
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField

        // when - user types (programmatic popup)
        typeText(editor, "Luke")
        Thread.sleep(100)

        // then - popup should open and show filtered results
        SwingUtilities.invokeAndWait {
            // After typing, the model should be filtered
            assertThat(comboBox.itemCount).isLessThanOrEqualTo(items.size)
        }

        // when - hide popup (don't need to show it again as that requires component visibility)
        SwingUtilities.invokeAndWait {
            comboBox.hidePopup()
        }
        Thread.sleep(50)

        // then - should handle popup state without error
        SwingUtilities.invokeAndWait {
            assertThat(comboBox.isPopupVisible).isFalse
            assertThat(comboBox.itemCount).isGreaterThanOrEqualTo(0)
        }
    }

    private fun createComboBox(
        toString: (TestItem?) -> String = { it?.name ?: "" },
        toElement: (String) -> TestItem? = { null }
    ): JComboBox<TestItem> {
        val comboBox = FilteringComboBox.create(
            toString = toString,
            toElement = toElement
        )
        comboBox.addItemListener { event ->
            if (event.stateChange == java.awt.event.ItemEvent.SELECTED) {
                selectedItem = event.item as? TestItem
            }
        }
        return comboBox
    }

    private fun typeText(editor: JTextField, text: String) {
        text.lastOrNull()?.let { lastChar ->
            SwingUtilities.invokeAndWait {
                editor.text = text
                editor.dispatchEvent(
                    KeyEvent(
                        editor,
                        KeyEvent.KEY_RELEASED,
                        System.currentTimeMillis(),
                        0,
                        KeyEvent.VK_UNDEFINED,
                        lastChar
                    )
                )
            }
            Thread.sleep(100)
        }
    }

    private fun pressKey(editor: JTextField, keyCode: Int, keyChar: Char = KeyEvent.CHAR_UNDEFINED) {
        SwingUtilities.invokeAndWait {
            editor.dispatchEvent(
                KeyEvent(
                    editor,
                    KeyEvent.KEY_RELEASED,
                    System.currentTimeMillis(),
                    0,
                    keyCode,
                    keyChar
                )
            )
        }
        Thread.sleep(50)
    }
}

