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
package com.redhat.devtools.gateway.auth.tls

import java.security.cert.X509Certificate

object TlsTestCertificates {

    // synthetic fixtures loaded from src/test/resources/tls/
    val CA_PEM: String = readTlsResource("ca-cert.pem")
    val EC_PEM: String = readTlsResource("ec-cert.pem")
    val NORMALIZATION_PEM: String = readTlsResource("normalization-cert.pem")
    // PKCS#8 RSA key; not paired with a live cluster
    val CLIENT_KEY_PEM: String = readTlsResource("client-key.pem")

    fun readTlsResource(name: String): String =
        TlsTestCertificates::class.java.classLoader
            .getResourceAsStream("tls/$name")
            ?.use { it.readBytes() }
            ?.toString(Charsets.UTF_8)
            ?: error("Missing TLS test resource: tls/$name")

    fun singleLine(pem: String): String = pem.replace("\n", "").replace("\r", "")

    fun caCertificate(): X509Certificate = PemUtils.parseCertificate(CA_PEM)

    fun caSourceFromData(): CertificateSource =
        CertificateSource.fromData(PemUtils.toBase64(CA_PEM))
}
