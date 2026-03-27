/*
 * Copyright (c) 2024-2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.auth.tls

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.ui.components.JBTextField
import java.awt.KeyboardFocusManager
import javax.swing.SwingUtilities

fun browseCertificate(textField: JBTextField, title: String) {
    val descriptor = FileChooserDescriptorFactory.singleFile()
        .withTitle(title)
        .withDescription("Select a certificate or key file")

    val parent = SwingUtilities.getWindowAncestor(textField)
        ?: KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow

    val chooser = FileChooser.chooseFile(
        descriptor,
        parent,
        null,
        null
    )

    chooser?.let { virtualFile ->
        textField.text = virtualFile.path
    }
}
