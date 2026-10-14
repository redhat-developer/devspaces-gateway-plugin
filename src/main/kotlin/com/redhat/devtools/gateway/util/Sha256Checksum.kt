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

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val IO_BUFFER_SIZE = 64 * 1024

/**
 * A SHA-256 checksum that a downloaded file can be verified against, e.g. as published in a
 * `.sha256` sidecar file next to the download.
 */
@JvmInline
value class Sha256Checksum(val hex: String) {

    /** Whether the contents of [path] hash to this checksum. */
    fun matches(path: Path): Boolean = sha256Hex(path).equals(hex, ignoreCase = true)

    companion object {
        private const val HEX_LENGTH = 64

        /**
         * Parses the first whitespace-separated token of a checksum file's [content]; returns
         * null when that is not a plausible SHA-256 (wrong length or non-hex characters).
         */
        fun parse(content: String): Sha256Checksum? {
            val checksum = content.trim().split(Regex("\\s+")).firstOrNull()
                ?.takeIf { token ->
                    token.length == HEX_LENGTH &&
                        token.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                }
                ?: return null
            return Sha256Checksum(checksum.lowercase())
        }

        private fun sha256Hex(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    digest.update(buffer, 0, bytesRead)
                }
            }
            return digest.digest().joinToString(separator = "") { "%02x".format(it) }
        }
    }
}
