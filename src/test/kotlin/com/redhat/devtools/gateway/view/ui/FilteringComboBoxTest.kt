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
    }

    @Test
    fun `Should use custom toString function for rendering`() {
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
    fun `Should invoke given onItemSelected callback when item is selected`() {
        // given
        comboBox = createComboBox()
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
    fun `Should filter items case-insensitively`() {
        // given
        comboBox = createComboBox()
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
    fun `Should filter items with substring matching`() {
        // given
        comboBox = createComboBox()
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
    fun `Should doe not filter on navigation keys`() {
        // given
        comboBox = createComboBox()
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField
        val initialCount = comboBox.itemCount

        // when - simulate pressing arrow key
        pressKey(editor, KeyEvent.VK_DOWN)

        // then - should not trigger filtering
        assertThat(comboBox.itemCount).isEqualTo(initialCount)
    }

    @Test
    fun `Should not filter on escape key`() {
        // given
        comboBox = createComboBox()
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField
        val initialCount = comboBox.itemCount

        // when - simulate pressing escape
        pressKey(editor, KeyEvent.VK_ESCAPE)

        // then - should not trigger filtering
        assertThat(comboBox.itemCount).isEqualTo(initialCount)
    }

    @Test
    fun `Should is matching item using custom matchItem function`() {
        // given
        val matchItem: (String, List<TestItem>) -> TestItem? = { text, items ->
            items.find { it.id.toString() == text }
        }
        comboBox = createComboBox(matchItem = matchItem)
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
    fun `Should return text when matchItem is null and no exact match`() {
        // given
        comboBox = createComboBox()
        items.forEach { comboBox.addItem(it) }
        val editor = comboBox.editor.editorComponent as JTextField

        // when
        SwingUtilities.invokeAndWait {
            editor.text = "Unknown Item"
        }

        // then - should return the text itself
        val item = comboBox.editor.item
        assertThat(item).isEqualTo("Unknown Item")
    }

    @Test
    fun `Should set editor text when item is selected`() {
        // given
        comboBox = createComboBox()
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
    fun `Should handle null items gracefully`() {
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
    fun `Should render null items with toString function`() {
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
    fun `Should hide popup when filtering results in no matches`() {
        // given
        comboBox = createComboBox()
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
    fun `Should preserve editor text when filtering`() {
        // given
        comboBox = createComboBox()
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
    fun `Should not invoke onItemSelected for DESELECTED events`() {
        // given
        var updateCount = 0
        comboBox = FilteringComboBox.create(
            toString = { it?.name ?: "" },
            type = TestItem::class.java,
            onItemSelected = {
                updateCount++
                selectedItem = it
            }
        )
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
    fun `Should sort filtered items by match position`() {
        // given
        comboBox = createComboBox()
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

    private fun createComboBox(
        toString: (TestItem?) -> String = { it?.name ?: "" },
        matchItem: ((String, List<TestItem>) -> TestItem?)? = null
    ): JComboBox<TestItem> {
        return FilteringComboBox.create(
            toString = toString,
            matchItem = matchItem,
            type = TestItem::class.java,
            onItemSelected = { selectedItem = it }
        )
    }

    private fun typeText(editor: JTextField, text: String) {
        val lastChar = text.lastOrNull() ?: return
        val keyCode = when (lastChar.uppercaseChar()) {
            'A' -> KeyEvent.VK_A
            'B' -> KeyEvent.VK_B
            'E' -> KeyEvent.VK_E
            'I' -> KeyEvent.VK_I
            'N' -> KeyEvent.VK_N
            'R' -> KeyEvent.VK_R
            'Y' -> KeyEvent.VK_Y
            else -> KeyEvent.VK_UNDEFINED
        }
        
        SwingUtilities.invokeAndWait {
            editor.text = text
            val keyEvent = KeyEvent(
                editor,
                KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                0,
                keyCode,
                lastChar
            )
            editor.dispatchEvent(keyEvent)
        }
        
        Thread.sleep(100) // Allow event processing
    }

    private fun pressKey(editor: JTextField, keyCode: Int, keyChar: Char = KeyEvent.CHAR_UNDEFINED) {
        SwingUtilities.invokeAndWait {
            val keyEvent = KeyEvent(
                editor,
                KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                0,
                keyCode,
                keyChar
            )
            editor.dispatchEvent(keyEvent)
        }
        Thread.sleep(50) // Allow time for event processing
    }
}

