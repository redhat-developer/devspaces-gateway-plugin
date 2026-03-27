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

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isReadable

/**
 * Represents a certificate or key source, tracking both the value and its format.
 *
 * @property value The certificate/key data - either a file path or base64-encoded content
 * @property isFilePath True if value is a file path, false if it's base64 data
 */
data class CertificateSource(
    val value: String,
    val isFilePath: Boolean
) {
    companion object {
        /**
         * Creates a CertificateSource from kubeconfig data field (base64).
         */
        fun fromData(data: String): CertificateSource {
            return CertificateSource(
                value = data,
                isFilePath = false
            )
        }

        /**
         * Creates a CertificateSource from kubeconfig path field.
         */
        fun fromPath(path: String): CertificateSource {
            return CertificateSource(
                value = path,
                isFilePath = true
            )
        }

        /**
         * Detects input type and creates appropriate CertificateSource.
         *
         * If input is PEM content (not a file path), it will be normalized
         * (newlines fixed if needed) and base64-encoded for storage in kubeconfig.
         *
         * Returns null if input is null or blank.
         */
        fun fromPathOrPem(input: String?): CertificateSource? {
            if (input.isNullOrBlank()) {
                return null
            }

            val trimmed = input.trim()
            val isPath = isPath(trimmed)

            val value = if (isPath) {
                expandPath(trimmed)
            } else {
                // It's PEM or base64 content - normalize and ensure base64-encoded
                val normalizedPem = if (PemUtils.isPem(trimmed)) {
                    PemUtils.ensureProperFormat(trimmed)
                } else {
                    trimmed // Already base64
                }
                PemUtils.toBase64(normalizedPem)
            }

            return CertificateSource(
                value = value,
                isFilePath = isPath
            )
        }

        private fun isPath(input: String): Boolean {
            // Check for path-like patterns
            return when {
                input.startsWith("/") -> true
                input.startsWith("~/") -> true
                input.startsWith("./") -> true
                input.startsWith("../") -> true
                input.matches(Regex("^[A-Z]:\\\\.*")) -> true  // Windows path
                PemUtils.isPem(input) -> false
                input.length < 50 && input.matches(Regex("^[a-zA-Z0-9._-]+$")) -> true
                input.length > 50 && input.matches(Regex("^[A-Za-z0-9+/=\\s]+$")) -> false  // Base64 (min length)
                else -> true  // Default to path for ambiguous cases
            }
        }

        private fun expandPath(path: String): String {
            return when {
                path.startsWith("~/") -> {
                    val home = System.getProperty("user.home")
                    home + path.substring(1)
                }
                else -> path
            }
        }
    }

    /**
     * Returns the value as a Path if it represents a file path.
     */
    fun toPath(): Path = Paths.get(value)

    /**
     * Validates the certificate source.
     * For file paths, checks existence and readability.
     * For base64 data, ensures it's not empty.
     */
    fun validate() {
        if (isFilePath) {
            val path = toPath()
            if (!path.exists()) {
                throw java.io.FileNotFoundException("Certificate file not found: $value")
            }
            if (!path.isReadable()) {
                throw java.io.IOException("Cannot read certificate file: $value")
            }
        }
    }
}
