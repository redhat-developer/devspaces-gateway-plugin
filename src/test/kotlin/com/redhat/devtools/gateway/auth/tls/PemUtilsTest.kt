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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

class PemUtilsTest {

    private val inventedNonParseablePem =
        "-----BEGIN CERTIFICATE-----\nTk9OLVZBTElELURFUi1ERVItVEVTVA==\n-----END CERTIFICATE-----" // notsecret

    @Test
    fun `#toBase64 encodes PEM content`() {
        // given
        val pem = inventedNonParseablePem
        // when
        val result = PemUtils.toBase64(pem)
        // then
        assertThat(result).isEqualTo(Base64.getEncoder().encodeToString(pem.toByteArray()))
    }

    @Test
    fun `#toBase64 passes through already-base64 content`() {
        // given
        val base64 = "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSURhekNDQWxP" // notsecret
        // when
        val result = PemUtils.toBase64(base64)
        // then
        assertThat(result).isEqualTo(base64)
    }

    @Test
    fun `#toBase64 is idempotent`() {
        // given
        val pem = inventedNonParseablePem
        // when
        val encoded = PemUtils.toBase64(pem)
        val doubleEncoded = PemUtils.toBase64(encoded)
        // then
        assertThat(doubleEncoded).isEqualTo(encoded)
    }

    @Test
    fun `#isPem returns true for PEM content`() {
        assertThat(PemUtils.isPem("-----BEGIN CERTIFICATE-----\ndata")).isTrue() // notsecret
    }

    @Test
    fun `#isPem returns true for PEM embedded in other text`() {
        assertThat(PemUtils.isPem("some prefix -----BEGIN CERTIFICATE-----")).isTrue() // notsecret
    }

    @Test
    fun `#isPem returns false for base64 content`() {
        assertThat(PemUtils.isPem("LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0t")).isFalse() // notsecret
    }

    @Test
    fun `#isPem returns false for plain text`() {
        assertThat(PemUtils.isPem("just some random text")).isFalse()
    }

    @Test
    fun `#isPem returns false for empty string`() {
        assertThat(PemUtils.isPem("")).isFalse()
    }

    @Test
    fun `#parseCertificate handles single-line PEM from JBTextField paste`() {
        // given - simulates pasting multi-line PEM into single-line JBTextField (newlines stripped)
        val singleLinePem = TlsTestCertificates.singleLine(TlsTestCertificates.CA_PEM)
        // when
        val certificate = PemUtils.parseCertificate(singleLinePem)
        // then — self-signed RSA fixture generated for this test suite only (openssl req -x509 … *.invalid)
        assertThat(certificate).isNotNull()
        assertThat(certificate.subjectX500Principal.name).contains("CN=fake-unit-test.example.invalid")
        assertThat(certificate.issuerX500Principal.name).contains("CN=fake-unit-test.example.invalid")
    }

    @Test
    fun `#parseCertificate handles properly formatted multi-line PEM`() {
        // given - proper synthetic PEM with newlines
        val multiLinePem = TlsTestCertificates.CA_PEM
        // when
        val certificate = PemUtils.parseCertificate(multiLinePem)
        //then
        assertThat(certificate).isNotNull()
        assertThat(certificate.subjectX500Principal.name).contains("CN=fake-unit-test.example.invalid")
    }

    @Test
    fun `#parseCertificate handles single-line EC certificate`() {
        // given
        val singleLineEcPem = TlsTestCertificates.singleLine(TlsTestCertificates.EC_PEM)
        // when
        val certificate = PemUtils.parseCertificate(singleLineEcPem)
        // then
        assertThat(certificate).isNotNull()
        assertThat(certificate.subjectX500Principal.name).contains("CN=fake-ec-unit-test.example.invalid")
    }

    @Test
    fun `#parsePrivateKey handles single-line RSA private key from JBTextField paste`() {
        // PKCS#8 PEM is a throwaway RSA key generated only for this test (openssl genrsa /
        // pkcs8 -nocrypt). It is not paired with any certificate in this file, never belonged to
        // a cluster or host
        val singleLineKey = TlsTestCertificates.singleLine(TlsTestCertificates.CLIENT_KEY_PEM)
        // when
        val privateKey = PemUtils.parsePrivateKey(singleLineKey)
        // then
        assertThat(privateKey).isNotNull()
        assertThat(privateKey.algorithm).isEqualTo("RSA")
    }

    @Test
    fun `#parseCertificate handles malformed PEM with only header newline`() {
        // given
        // header newline only; body and footer on one line
        val malformedPem = TlsTestCertificates.singleLine(TlsTestCertificates.CA_PEM)
            .replace("-----BEGIN CERTIFICATE-----", "-----BEGIN CERTIFICATE-----\n") // notsecret
        // when
        val certificate = PemUtils.parseCertificate(malformedPem)
        // then
        assertThat(certificate).isNotNull()
        assertThat(certificate.subjectX500Principal.name).contains("CN=fake-unit-test.example.invalid")
    }
}
