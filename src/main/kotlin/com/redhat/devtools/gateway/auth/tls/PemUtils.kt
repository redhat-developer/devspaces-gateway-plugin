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

    fun toPem(certificate: X509Certificate): String {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(certificate.encoded)

        return buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            append(base64)
            append("\n-----END CERTIFICATE-----\n")
        }
    }

    fun parsePrivateKey(pemOrBase64: String): PrivateKey {
        val normalized = normalizePem(pemOrBase64)

        val cleaned = normalized
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val decoded = Base64.getDecoder().decode(cleaned)

        return try {
            // Try PKCS#8 first
            val spec = PKCS8EncodedKeySpec(decoded)
            KeyFactory.getInstance("RSA").generatePrivate(spec)
        } catch (_: Exception) {
            // Try EC
            try {
                val spec = PKCS8EncodedKeySpec(decoded)
                KeyFactory.getInstance("EC").generatePrivate(spec)
            } catch (e: Exception) {
                throw IllegalArgumentException("Unsupported private key format", e)
            }
        }
    }

    private fun normalizePem(input: String): String {
        val trimmed = input.trim()

        return if (!trimmed.contains("BEGIN")) {
            // It's base64 from kubeconfig → decode to PEM
            String(Base64.getDecoder().decode(trimmed))
        } else {
            trimmed
        }
    }

    fun parseCertificate(pemOrBase64: String): X509Certificate {
        val normalized = normalizePem(pemOrBase64)

        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(
            normalized.byteInputStream()
        ) as X509Certificate
    }
}
