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

    @Test
    fun `#toBase64 encodes PEM content`() {
        val pem = "-----BEGIN CERTIFICATE-----\nMIIDazCCAlOgAwIBAgIUZtx\n-----END CERTIFICATE-----"

        val result = PemUtils.toBase64(pem)

        assertThat(result).isEqualTo(Base64.getEncoder().encodeToString(pem.toByteArray()))
    }

    @Test
    fun `#toBase64 passes through already-base64 content`() {
        val base64 = "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSURhekNDQWxP"

        val result = PemUtils.toBase64(base64)

        assertThat(result).isEqualTo(base64)
    }

    @Test
    fun `#toBase64 is idempotent`() {
        val pem = "-----BEGIN CERTIFICATE-----\nMIIDazCCAlOgAwIBAgIUZtx\n-----END CERTIFICATE-----"

        val encoded = PemUtils.toBase64(pem)
        val doubleEncoded = PemUtils.toBase64(encoded)

        assertThat(doubleEncoded).isEqualTo(encoded)
    }

    @Test
    fun `#isPem returns true for PEM content`() {
        assertThat(PemUtils.isPem("-----BEGIN CERTIFICATE-----\ndata")).isTrue()
    }

    @Test
    fun `#isPem returns true for PEM embedded in other text`() {
        assertThat(PemUtils.isPem("some prefix -----BEGIN CERTIFICATE-----")).isTrue()
    }

    @Test
    fun `#isPem returns false for base64 content`() {
        assertThat(PemUtils.isPem("LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0t")).isFalse()
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
        // given - simulates pasting multi-line PEM into single-line JBTextField
        // (newlines get stripped, resulting in single-line PEM)
        val singleLinePem = "-----BEGIN CERTIFICATE-----MIIB9zCCAXygAwIBAgIUALZNAPFdxHPwjeDloDwyYChAO/4wCgYIKoZIzj0EAwMwKjEVMBMGA1UEChMMc2lnc3RvcmUuZGV2MREwDwYDVQQDEwhzaWdzdG9yZTAeFw0yMTEwMDcxMzU2NTlaFw0zMTEwMDUxMzU2NThaMCoxFTATBgNVBAoTDHNpZ3N0b3JlLmRldjERMA8GA1UEAxMIc2lnc3RvcmUwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAAT7XeFT4rb3PQGwS4IajtLk3/OlnpgangaBclYpsYBr5i+4ynB07ceb3LP0OIOZdxexX69c5iVuyJRQ+Hz05yi+UF3uBWAlHpiS5sh0+H2GHE7SXrk1EC5m1Tr19L9gg92jYzBhMA4GA1UdDwEB/wQEAwIBBjAPBgNVHRMBAf8EBTADAQH/MB0GA1UdDgQWBBRYwB5fkUWlZql6zJChkyLQKsXF+jAfBgNVHSMEGDAWgBRYwB5fkUWlZql6zJChkyLQKsXF+jAKBggqhkjOPQQDAwNpADBmAjEAj1nHeXZp+13NWBNa+EDsDP8G1WWg1tCMWP/WHPqpaVo0jhsweNFZgSs0eE7wYI4qAjEA2WB9ot98sIkoF3vZYdd3/VtWB5b9TNMea7Ix/stJ5TfcLLeABLE4BNJOsQ4vnBHJ-----END CERTIFICATE-----"

        // when
        val certificate = PemUtils.parseCertificate(singleLinePem)

        // then
        assertThat(certificate).isNotNull()
        assertThat(certificate.subjectX500Principal.name).contains("CN=sigstore")
        assertThat(certificate.issuerX500Principal.name).contains("CN=sigstore")
    }

    @Test
    fun `#parseCertificate handles properly formatted multi-line PEM`() {
        // given - proper PEM with newlines
        val multiLinePem = """
            -----BEGIN CERTIFICATE-----
            MIIB9zCCAXygAwIBAgIUALZNAPFdxHPwjeDloDwyYChAO/4wCgYIKoZIzj0EAwMw
            KjEVMBMGA1UEChMMc2lnc3RvcmUuZGV2MREwDwYDVQQDEwhzaWdzdG9yZTAeFw0y
            MTEwMDcxMzU2NTlaFw0zMTEwMDUxMzU2NThaMCoxFTATBgNVBAoTDHNpZ3N0b3Jl
            LmRldjERMA8GA1UEAxMIc2lnc3RvcmUwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAAT7
            XeFT4rb3PQGwS4IajtLk3/OlnpgangaBclYpsYBr5i+4ynB07ceb3LP0OIOZdxex
            X69c5iVuyJRQ+Hz05yi+UF3uBWAlHpiS5sh0+H2GHE7SXrk1EC5m1Tr19L9gg92j
            YzBhMA4GA1UdDwEB/wQEAwIBBjAPBgNVHRMBAf8EBTADAQH/MB0GA1UdDgQWBBRY
            wB5fkUWlZql6zJChkyLQKsXF+jAfBgNVHSMEGDAWgBRYwB5fkUWlZql6zJChkyLQ
            KsXF+jAKBggqhkjOPQQDAwNpADBmAjEAj1nHeXZp+13NWBNa+EDsDP8G1WWg1tCM
            WP/WHPqpaVo0jhsweNFZgSs0eE7wYI4qAjEA2WB9ot98sIkoF3vZYdd3/VtWB5b9
            TNMea7Ix/stJ5TfcLLeABLE4BNJOsQ4vnBHJ
            -----END CERTIFICATE-----
        """.trimIndent()

        // when
        val certificate = PemUtils.parseCertificate(multiLinePem)

        // then
        assertThat(certificate).isNotNull()
        assertThat(certificate.subjectX500Principal.name).contains("CN=sigstore")
        assertThat(certificate.issuerX500Principal.name).contains("CN=sigstore")
    }

    @Test
    fun `#parseCertificate handles single-line client certificate`() {
        // given - client certificate pasted into JBTextField (newlines stripped)
        val singleLinePem = "-----BEGIN CERTIFICATE-----MIIDGjCCAgKgAwIBAgIBAjANBgkqhkiG9w0BAQsFADAiMSAwHgYDVQQDDBdsb2NhbGhvc3QtY2FAMTUzMTQ2ODA4NTAgFw0xODA3MTMwNjQ4MDVaGA8yMTE4MDYxOTA2NDgwNVowHzEdMBsGA1UEAwwUbG9jYWxob3N0QDE1MzE0NjgwODYwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDC9Qfx1YAEp+wrSIjbinWw3pWIDbf57LutfXgS84ilZpc7M2zeu1QrPyhCedL/gPP0QxKbPS6AR5R/DibH4RWcujL6CU5FB0Y9on+IpN/Iml2XzgGiU82gTkJg185VgWwDaHOPKvUF9N1GpvxcSvRsNGoiBJ/LlE4NhxyUQ0V/lAalYxYybxgl8/xghWMkGnQc3YKWKqGmtBaaax3xvMzamxpWPphoLG07+YZfAf0Q7vslVMmlslRmx9OpJFvRnkelbXoHHx73umbMiFp28njY8NK2dqXwb6Z80BCezppCKYpbjnupOIDAAE0KvjzhhzSS68ZgukiBZOcUlnWLzL39AgMBAAGjXDBaMA4GA1UdDwEB/wQEAwIFoDATBgNVHSUEDDAKBggrBgEFBQcDATAMBgNVHRMBAf8EAjAAMCUGA1UdEQQeMByCCWxvY2FsaG9zdIIJbG9jYWxob3N0hwR/AAABMA0GCSqGSIb3DQEBCwUAA4IBAQBm9Z15QxsRqoaRDh/ELA93eE9105gwrXrR3AK/iKuJyxIc/SXVbpAaYHArMrUzaZs0GXEzgW31tZn8D3dgFy8XdZxk1ztaFTm+QTnFRogMNB8Akvpq7jwTa44c7G0wuNO2nATMu2Ifi/nSdQadTxzmZacSrevN/zcjmvSoV4VFkKO5VBnr7e1ruffxAaVAzrRraplpZvuJzlcvqTYAME8fq8H9QidvaXF6yIbEPwwUHK678W4rXn9Zp6NDuQhH0eNPAGlEAaYuyCJvJZeM68ootMi7Uh6RJOTDw1HIdekYEr1/FAg4+rH/9Gi+o/LXsBmYXabO+GjsOwfezv3THDnw-----END CERTIFICATE-----"

        // when
        val certificate = PemUtils.parseCertificate(singleLinePem)

        // then
        assertThat(certificate).isNotNull()
        assertThat(certificate.subjectX500Principal.name).contains("CN=localhost@1531468086")
    }

    @Test
    fun `#parsePrivateKey handles single-line RSA private key from JBTextField paste`() {
        // given - 512-bit RSA private key in single-line format (simulating JBTextField paste)
        val singleLineKey = "-----BEGIN PRIVATE KEY-----MIIBVwIBADANBgkqhkiG9w0BAQEFAASCAUEwggE9AgEAAkEA8ZF6nT2T9jLuZE6c8CduV0pc0MpCzZkLGKH4KMDjd9J5L/c/DqbrqqubGCfZhWfpn7Dccy1QEVmJf5RgLbxL6QIDAQABAkEAkphhW2jSENdJmi+mx4p2SJzFBKOptJEKjdFFAp5DrCMtOf0SgGPqadEJmVF+FcsFJKi0RXN+0Hk0JIXUoRBoXQIhAPmYugieyFBgcO17xEfe+Bm1rIBMg0DGVQVaxZmJ1LqXAiEA98QGWSQ6OTuR0U0KkgjWEyfRdtf5rOmTk+I2VL2ofX8CIQCCtQg3G2+rJ9X7h6TyPkGOtSTwyyCw+yvq8e4oyZUtYQIhAMZnMq4vVHCAQ0RXbR+D8+li+Vkxmb3dTVAe1WMGfOYBAiEA1axQLnkgglVgK9jGYUgH320TpEwLuOKQCwysxqVg9jQ=-----END PRIVATE KEY-----"

        // when
        val privateKey = PemUtils.parsePrivateKey(singleLineKey)

        // then
        assertThat(privateKey).isNotNull()
        assertThat(privateKey.algorithm).isEqualTo("RSA")
    }

    @Test
    fun `#parseCertificate handles malformed PEM with only header newline`() {
        // given - JBTextField sometimes preserves first newline only
        val malformedPem = "-----BEGIN CERTIFICATE-----\nMIIDGjCCAgKgAwIBAgIBAjANBgkqhkiG9w0BAQsFADAiMSAwHgYDVQQDDBdsb2NhbGhvc3QtY2FAMTUzMTQ2ODA4NTAgFw0xODA3MTMwNjQ4MDVaGA8yMTE4MDYxOTA2NDgwNVowHzEdMBsGA1UEAwwUbG9jYWxob3N0QDE1MzE0NjgwODYwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDC9Qfx1YAEp+wrSIjbinWw3pWIDbf57LutfXgS84ilZpc7M2zeu1QrPyhCedL/gPP0QxKbPS6AR5R/DibH4RWcujL6CU5FB0Y9on+IpN/Iml2XzgGiU82gTkJg185VgWwDaHOPKvUF9N1GpvxcSvRsNGoiBJ/LlE4NhxyUQ0V/lAalYxYybxgl8/xghWMkGnQc3YKWKqGmtBaaax3xvMzamxpWPphoLG07+YZfAf0Q7vslVMmlslRmx9OpJFvRnkelbXoHHx73umbMiFp28njY8NK2dqXwb6Z80BCezppCKYpbjnupOIDAAE0KvjzhhzSS68ZgukiBZOcUlnWLzL39AgMBAAGjXDBaMA4GA1UdDwEB/wQEAwIFoDATBgNVHSUEDDAKBggrBgEFBQcDATAMBgNVHRMBAf8EAjAAMCUGA1UdEQQeMByCCWxvY2FsaG9zdIIJbG9jYWxob3N0hwR/AAABMA0GCSqGSIb3DQEBCwUAA4IBAQBm9Z15QxsRqoaRDh/ELA93eE9105gwrXrR3AK/iKuJyxIc/SXVbpAaYHArMrUzaZs0GXEzgW31tZn8D3dgFy8XdZxk1ztaFTm+QTnFRogMNB8Akvpq7jwTa44c7G0wuNO2nATMu2Ifi/nSdQadTxzmZacSrevN/zcjmvSoV4VFkKO5VBnr7e1ruffxAaVAzrRraplpZvuJzlcvqTYAME8fq8H9QidvaXF6yIbEPwwUHK678W4rXn9Zp6NDuQhH0eNPAGlEAaYuyCJvJZeM68ootMi7Uh6RJOTDw1HIdekYEr1/FAg4+rH/9Gi+o/LXsBmYXabO+GjsOwfezv3THDnw-----END CERTIFICATE-----"

        // when
        val certificate = PemUtils.parseCertificate(malformedPem)

        // then
        assertThat(certificate).isNotNull()
        assertThat(certificate.subjectX500Principal.name).contains("CN=localhost@1531468086")
    }
}
