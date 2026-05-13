/*
 * Copyright (c) 2025-2026 Red Hat, Inc.
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

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*

object PemUtils {

    /**
     * Detects whether content is PEM-encoded (as opposed to base64).
     *
     * @param content The content to check whether it's PEM-encoded.
     * @returns `true` if the given string is PEM-encoded. Returns `false` otherwise.
     */
    fun isPem(content: String): Boolean = content.contains("-----BEGIN")

    /**
     * Ensures PEM has proper formatting with newlines.
     * Reformats single-line or malformed PEM (e.g., from JBTextField paste).
     */
    fun ensureProperFormat(pem: String): String {
        val newlineCount = pem.count { it == '\n' }
        return if (newlineCount < 2) {
            reformatSingleLinePem(pem)
        } else {
            pem
        }
    }

    fun toPem(certificate: X509Certificate): String {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(certificate.encoded)

        return buildString {
            // notsecret
            append("-----BEGIN CERTIFICATE-----\n")
            append(base64)
            append("\n-----END CERTIFICATE-----\n")
        }
    }

    /**
     * Base64-encodes PEM content; passes through already-base64 content unchanged.
     */
    fun toBase64(value: String): String {
        return if (isPem(value)) {
            Base64.getEncoder().encodeToString(value.toByteArray())
        } else {
            value
        }
    }

    fun parsePrivateKey(pemOrBase64: String): PrivateKey {
        val normalized = normalizePem(pemOrBase64)
        // JBTextField can strip or mangle newlines from pasted PEM → reformat if needed
        val reformatted = clean(normalized)

        // notsecret
        val cleaned = reformatted
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN EC PRIVATE KEY-----", "")
            .replace("-----END EC PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val decoded = Base64.getDecoder().decode(cleaned)

        return try {
            // Try PKCS#8 first
            generatePrivateKey(decoded, "RSA")
        } catch (_: Exception) {
            // Try EC
            try {
                generatePrivateKey(decoded, "EC")
            } catch (e: Exception) {
                throw IllegalArgumentException("Unsupported private key format", e)
            }
        }
    }

    private fun generatePrivateKey(encodedKey: ByteArray, algorithm: String): PrivateKey {
        val spec = PKCS8EncodedKeySpec(encodedKey)
        return KeyFactory.getInstance(algorithm).generatePrivate(spec)
    }

    private fun normalizePem(input: String): String {
        val trimmed = input.trim()

        return if (!isPem(trimmed)) {
            // kubeconfig/base64 -> PEM
            String(Base64.getDecoder().decode(trimmed))
        } else {
            trimmed
        }
    }

    fun parseCertificate(pemOrBase64: String): X509Certificate {
        val normalized = normalizePem(pemOrBase64)

        // JBTextField can strip or mangle newlines from pasted PEM → reformat if needed
        val cleaned = clean(normalized)

        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(cleaned.byteInputStream()) as X509Certificate
    }

    private fun clean(normalized: String): String {
        return if (isPem(normalized)) {
            // Count newlines - properly formatted PEM should have multiple lines
            val newlineCount = normalized.count { it == '\n' }
            // Reformat if single-line or has very few newlines (malformed)
            if (newlineCount < 2) {
                reformatSingleLinePem(normalized)
            } else {
                normalized
            }
        } else {
            normalized
        }
    }

    /**
     * Reformats PEM that has newlines stripped or malformed (from text field paste).
     * Extracts base64 content and rebuilds proper PEM format with line breaks.
     * Handles all PEM types (CERTIFICATE, PRIVATE KEY, RSA PRIVATE KEY, EC PRIVATE KEY, etc.).
     */
    private fun reformatSingleLinePem(singleLinePem: String): String {
        // Match any PEM header/footer (CERTIFICATE, PRIVATE KEY, PUBLIC KEY, etc.)
        val beginMarker = Regex("-----BEGIN [A-Z ]+-----")
        val endMarker = Regex("-----END [A-Z ]+-----")

        val beginMatch = beginMarker.find(singleLinePem) ?: return singleLinePem
        val endMatch = endMarker.find(singleLinePem) ?: return singleLinePem

        val header = beginMatch.value
        val footer = endMatch.value
        val base64Content = singleLinePem.substring(
            beginMatch.range.last + 1,
            endMatch.range.first
        ).replace("\\s+".toRegex(), "") // Remove all whitespace (spaces, newlines, tabs)

        if (base64Content.isEmpty()) {
            throw IllegalArgumentException("No certificate data found between PEM markers")
        }

        // Rebuild with proper line breaks (64 chars per line, standard PEM format)
        val formattedBase64 = base64Content.chunked(64).joinToString("\n")

        return "$header\n$formattedBase64\n$footer\n"
    }
}
