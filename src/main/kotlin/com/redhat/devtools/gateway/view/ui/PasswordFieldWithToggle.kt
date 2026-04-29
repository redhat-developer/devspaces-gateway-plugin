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

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JLayeredPane
import javax.swing.JPanel

/**
 * A password field with an integrated show/hide toggle button.
 * The eye icon button appears inside the field on the right side.
 */
class PasswordFieldWithToggle : JPanel() {

    val passwordField = JBPasswordField().apply {
        margin = JBUI.insets(0, 5, 0, 30)
    }

    private val toggleButton = JButton().apply {
        icon = AllIcons.General.InspectionsEye
        toolTipText = "Show/Hide password"
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isOpaque = false
        margin = JBUI.emptyInsets()
        size = Dimension(20, 20)
        preferredSize = Dimension(20, 20)
        minimumSize = Dimension(20, 20)
        maximumSize = Dimension(20, 20)
        addActionListener {
            val isVisible = passwordField.echoChar == 0.toChar()
            passwordField.echoChar = if (isVisible) '•' else 0.toChar()
            passwordField.requestFocusInWindow()
        }
    }

    init {
        layout = null
        isOpaque = false
        background = null

        // Add password field first
        add(passwordField)

        // Add button directly to password field so it renders on top
        passwordField.layout = null
        passwordField.add(toggleButton)
    }

    override fun paintComponent(g: java.awt.Graphics) {
        // Don't paint background - stay transparent
    }

    override fun doLayout() {
        super.doLayout()

        passwordField.setBounds(0, 0, width, height)

        layoutToggle()
    }

    private fun layoutToggle() {
        // Position button inside password field on the right
        val buttonWidth = 20
        val buttonHeight = 20
        val x = passwordField.width - buttonWidth - 5
        val y = (passwordField.height - buttonHeight) / 2
        toggleButton.setBounds(x, y, buttonWidth, buttonHeight)
    }


    override fun getPreferredSize(): Dimension {
        return passwordField.preferredSize
    }

    override fun getMinimumSize(): Dimension {
        return passwordField.minimumSize
    }

    override fun getMaximumSize(): Dimension {
        return passwordField.maximumSize
    }

    /**
     * Set custom tooltip text for the toggle button.
     */
    fun setToggleButtonTooltip(tooltip: String) {
        toggleButton.toolTipText = tooltip
    }
}
