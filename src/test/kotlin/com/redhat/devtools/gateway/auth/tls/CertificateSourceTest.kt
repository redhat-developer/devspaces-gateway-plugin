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

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.*
import java.nio.file.Paths

class CertificateSourceTest {

    @Test
    fun `#fromData creates source with isFilePath false`() {
        // given
        // when
        val source = CertificateSource.fromData("c29tZS1kYXRh")

        // then
        assertThat(source.value).isEqualTo("c29tZS1kYXRh")
        assertThat(source.isFilePath).isFalse()
    }

    @Test
    fun `#fromPath creates source with isFilePath true`() {
        // given
        // when
        val source = CertificateSource.fromPath("/home/user/cert.pem")

        // then
        assertThat(source.value).isEqualTo("/home/user/cert.pem")
        assertThat(source.isFilePath).isTrue()
    }

    @Test
    fun `#fromPathOrPem detects absolute Unix path`() {
        // given
        // when
        val source = CertificateSource.fromPathOrPem("/home/user/.minikube/ca.crt")!!

        // then
        assertThat(source.isFilePath).isTrue()
        assertThat(source.value).isEqualTo("/home/user/.minikube/ca.crt")
    }

    @Test
    fun `#fromPathOrPem detects home path`() {
        // given
        // when
        val source = CertificateSource.fromPathOrPem("~/.kube/config")!!

        // then
        assertThat(source.isFilePath).isTrue()
        assertThat(source.value).startsWith(System.getProperty("user.home"))
    }

    @Test
    fun `#fromPathOrPem detects relative path starting with dot`() {
        // given
        // when
        val source = CertificateSource.fromPathOrPem("./certs/ca.pem")!!

        // then
        assertThat(source.isFilePath).isTrue()
    }

    @Test
    fun `#fromPathOrPem detects parent relative path`() {
        // given
        // when
        val source = CertificateSource.fromPathOrPem("../shared/cert.pem")!!

        // then
        assertThat(source.isFilePath).isTrue()
    }

    @Test
    fun `#fromPathOrPem detects Windows path`() {
        // given
        // when
        val source = CertificateSource.fromPathOrPem("C:\\Users\\user\\cert.pem")!!

        // then
        assertThat(source.isFilePath).isTrue()
    }

    @Test
    fun `#fromPathOrPem detects simple filename`() {
        // given
        // when
        val source = CertificateSource.fromPathOrPem("cert.pem")!!

        // then
        assertThat(source.isFilePath).isTrue()
    }

    @Test
    fun `#fromPathOrPem detects filename without extension`() {
        // given
        // when
        val source = CertificateSource.fromPathOrPem("mycert")!!

        // then
        assertThat(source.isFilePath).isTrue()
    }

    @Test
    fun `#fromPathOrPem detects filename with hyphens and underscores`() {
        // given
        // when
        val source = CertificateSource.fromPathOrPem("my-cert_file.pem")!!

        // then
        assertThat(source.isFilePath).isTrue()
    }

    @Test
    fun `#fromPathOrPem detects PEM content`() {
        // given
        // when
        val source = CertificateSource.fromPathOrPem("-----BEGIN CERTIFICATE-----")!! // notsecret

        // then
        assertThat(source.isFilePath).isFalse()
    }

    @Test
    fun `#fromPathOrPem detects long base64 string`() {
        // given
        val base64 = "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSURhekNDQWxPZ0F3SUJBZ0lVWnR4" // notsecret

        // when
        val source = CertificateSource.fromPathOrPem(base64)!!

        // then
        assertThat(source.isFilePath).isFalse()
    }

    @Test
    fun `#fromPathOrPem trims whitespace`() {
        // given
        // when
        val source = CertificateSource.fromPathOrPem("  /path/to/cert  ")!!

        // then
        assertThat(source.value).isEqualTo("/path/to/cert")
    }

    @Test
    fun `#validate succeeds for base64 data`() {
        // given
        val source = CertificateSource.fromData("LS0tLS1CRUdJTi") // notsecret

        // when/then
        assertThatCode { source.validate() }.doesNotThrowAnyException()
    }

    @Test
    fun `#validate succeeds for existing file`() {
        // given
        val tempFile = java.io.File.createTempFile("test-cert", ".pem")
        try {
            val source = CertificateSource.fromPath(tempFile.absolutePath)

            // when
            // then
            assertThatCode { source.validate() }.doesNotThrowAnyException()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `#validate throws for non-existent file`() {
        // given
        val source = CertificateSource.fromPath("/non/existent/cert.pem")

        // when
        // then
        assertThatThrownBy { source.validate() }
            .isInstanceOf(java.io.FileNotFoundException::class.java)
            .hasMessageContaining("/non/existent/cert.pem")
    }

    @Test
    fun `#fromUserInput distinguishes short alphanumeric from base64`() {
        // given
        // when
        val source = CertificateSource.fromPathOrPem("cert")!!

        // then
        assertThat(source.isFilePath).isTrue()
    }

    @Test
    fun `#toPath returns correct Path`() {
        // given
        val source = CertificateSource.fromPath("/home/user/cert.pem")

        // when
        val path = source.toPath()

        // then
        assertThat(path).isEqualTo(Paths.get("/home/user/cert.pem"))
    }

    @Test
    fun `#fromUserInput normalizes and base64-encodes single-line PEM`() {
        // given — self-signed fixture for this suite only (openssl req … *.invalid), not from any cluster
        val singleLinePem = TlsTestCertificates.singleLine(TlsTestCertificates.NORMALIZATION_PEM)

        // when
        val source = CertificateSource.fromPathOrPem(singleLinePem)!!

        // then
        assertThat(source.isFilePath).isFalse()
        // Value should be base64-encoded (not raw PEM)
        assertThat(source.value).doesNotContain("-----BEGIN")
        assertThat(source.value).doesNotContain("-----END")
        // Should be valid base64
        assertThat(source.value).matches("^[A-Za-z0-9+/=\\s]+$")
    }

    @Test
    fun `#fromUserInput handles properly formatted multi-line PEM`() {
        // given - proper synthetic PEM with newlines
        val multiLinePem = TlsTestCertificates.NORMALIZATION_PEM

        // when
        val source = CertificateSource.fromPathOrPem(multiLinePem)!!

        // then
        assertThat(source.isFilePath).isFalse()
        // Value should be base64-encoded
        assertThat(source.value).doesNotContain("-----BEGIN")
    }

    @Test
    fun `#fromPathOrPem returns null for null input`() {
        // given
        // when
        val source = CertificateSource.fromPathOrPem(null)

        // then
        assertThat(source).isNull()
    }

    @Test
    fun `#fromPathOrPem returns null for empty string`() {
        // given
        // when
        val source = CertificateSource.fromPathOrPem("")

        // then
        assertThat(source).isNull()
    }

    @Test
    fun `#fromPathOrPem returns null for blank string`() {
        // given
        // when
        val source = CertificateSource.fromPathOrPem("   ")

        // then
        assertThat(source).isNull()
    }
}
