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
package com.redhat.devtools.gateway.util

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/**
 * Interface for reading text content from the system clipboard.
 * This abstraction allows for testing without requiring a display server.
 */
interface ClipboardReader {
    /**
     * Reads the current text content from the clipboard.
     *
     * @return The clipboard text or null if empty or not text
     */
    fun readText(): String?
}

/**
 * Default implementation that reads from the system clipboard.
 */
class SystemClipboardReader : ClipboardReader {
    override fun readText(): String? {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val contents = clipboard.getContents(null) ?: return null

        return try {
            if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                contents.getTransferData(DataFlavor.stringFlavor) as? String
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }?.trim()
    }
}
