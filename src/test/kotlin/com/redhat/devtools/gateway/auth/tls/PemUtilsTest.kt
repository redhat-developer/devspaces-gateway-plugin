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
        // notsecret
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
        // given - simulates pasting multi-line PEM into single-line JBTextField (newlines stripped)
        val singleLinePem =
            // notsecret
            "-----BEGIN CERTIFICATE-----MIIDlTCCAn2gAwIBAgIUJ/MyNwdZC5vGYJMyYa5m4letZrYwDQYJKoZIhvcNAQELBQAwWjEnMCUGA1UEAwweZmFrZS11bml0LXRlc3QuZXhhbXBsZS5pbnZhbGlkMSIwIAYDVQQKDBlFeGFtcGxlIFRlc3QgRml4dHVyZSBPbmx5MQswCQYDVQQGEwJYWDAeFw0yNjA1MTMxMzE4MDJaFw0zNjA1MTAxMzE4MDJaMFoxJzAlBgNVBAMMHmZha2UtdW5pdC10ZXN0LmV4YW1wbGUuaW52YWxpZDEiMCAGA1UECgwZRXhhbXBsZSBUZXN0IEZpeHR1cmUgT25seTELMAkGA1UEBhMCWFgwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCG4CRbIkDOtpWzjWVW3V62FKzSfdAhdOJ/avqaPU2FiSjwEcBuVceoT5ilVjNWuDSqWeTrmwPjBfzywpB9OHrziqE5rRBnlyuxTMgxxbpNU8WEBFtn2RWvKen0uZOOLTro1oQsI6ALqKd07s8t9XjIZMEiOzhvKzYK6xQiqXjnYJqWAw3ZjhuvPcuvAALTXJMB6dASZNJ+q7gUd0gIMIjXVzAcj/QPxISwr3JMbpk+GvDnz0kFt7TFQRMqW56dbK36ukjDvLdFd+bbigE6m55vsGVdyZC55wBIB87ycn0zc3hgrfej4JVEqEhhlsifUkjGqNR2h9cdY3u58gzJwZP5AgMBAAGjUzBRMB0GA1UdDgQWBBSn488Oxr0rTEaI1Q3xHhxERrAZ5jAfBgNVHSMEGDAWgBSn488Oxr0rTEaI1Q3xHhxERrAZ5jAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQAu0fWReMMgSMM2ctyslZ/b00FDUnDq713HQ+HH3sB28NVxvwKUHR637Z/VzX2HNlR5wuR2ulxKi6m54EBVCuE+T4kwPD/wx32RtGMAyuBlpamLC6WOdmVIVdYr66BRE7KdfTNnK+MJAa0duD5KniqxkdMU7ZxveHM6RRv/hDg0qybOxLSwetmfI9CRiw0qOGiX5PhCqsJVIf1FxRl2mPPO0HiI94AyenmZfatuz9Y8Pb/q7cgdXpX2x29dnqXXO91qbVHk+zIIsYowqsdnMTfqNHFSJGrNovvI63/GQ/8148oKAALaH4VgNOyVIdaKkPDR5I/WBnNmgJHFa/ozYnVi-----END CERTIFICATE-----"
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
        // notsecret
        val multiLinePem = """
            -----BEGIN CERTIFICATE-----
            MIIDlTCCAn2gAwIBAgIUJ/MyNwdZC5vGYJMyYa5m4letZrYwDQYJKoZIhvcNAQEL
            BQAwWjEnMCUGA1UEAwweZmFrZS11bml0LXRlc3QuZXhhbXBsZS5pbnZhbGlkMSIw
            IAYDVQQKDBlFeGFtcGxlIFRlc3QgRml4dHVyZSBPbmx5MQswCQYDVQQGEwJYWDAe
            Fw0yNjA1MTMxMzE4MDJaFw0zNjA1MTAxMzE4MDJaMFoxJzAlBgNVBAMMHmZha2Ut
            dW5pdC10ZXN0LmV4YW1wbGUuaW52YWxpZDEiMCAGA1UECgwZRXhhbXBsZSBUZXN0
            IEZpeHR1cmUgT25seTELMAkGA1UEBhMCWFgwggEiMA0GCSqGSIb3DQEBAQUAA4IB
            DwAwggEKAoIBAQCG4CRbIkDOtpWzjWVW3V62FKzSfdAhdOJ/avqaPU2FiSjwEcBu
            VceoT5ilVjNWuDSqWeTrmwPjBfzywpB9OHrziqE5rRBnlyuxTMgxxbpNU8WEBFtn
            2RWvKen0uZOOLTro1oQsI6ALqKd07s8t9XjIZMEiOzhvKzYK6xQiqXjnYJqWAw3Z
            jhuvPcuvAALTXJMB6dASZNJ+q7gUd0gIMIjXVzAcj/QPxISwr3JMbpk+GvDnz0kF
            t7TFQRMqW56dbK36ukjDvLdFd+bbigE6m55vsGVdyZC55wBIB87ycn0zc3hgrfej
            4JVEqEhhlsifUkjGqNR2h9cdY3u58gzJwZP5AgMBAAGjUzBRMB0GA1UdDgQWBBSn
            488Oxr0rTEaI1Q3xHhxERrAZ5jAfBgNVHSMEGDAWgBSn488Oxr0rTEaI1Q3xHhxE
            RrAZ5jAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQAu0fWReMMg
            SMM2ctyslZ/b00FDUnDq713HQ+HH3sB28NVxvwKUHR637Z/VzX2HNlR5wuR2ulxK
            i6m54EBVCuE+T4kwPD/wx32RtGMAyuBlpamLC6WOdmVIVdYr66BRE7KdfTNnK+MJ
            Aa0duD5KniqxkdMU7ZxveHM6RRv/hDg0qybOxLSwetmfI9CRiw0qOGiX5PhCqsJV
            If1FxRl2mPPO0HiI94AyenmZfatuz9Y8Pb/q7cgdXpX2x29dnqXXO91qbVHk+zII
            sYowqsdnMTfqNHFSJGrNovvI63/GQ/8148oKAALaH4VgNOyVIdaKkPDR5I/WBnNm
            gJHFa/ozYnVi
            -----END CERTIFICATE-----
        """.trimIndent()
        // when
        val certificate = PemUtils.parseCertificate(multiLinePem)
        //then
        assertThat(certificate).isNotNull()
        assertThat(certificate.subjectX500Principal.name).contains("CN=fake-unit-test.example.invalid")
    }

    @Test
    fun `#parseCertificate handles single-line EC certificate`() {
        // given
        // notsecret
        val singleLineEcPem =
            "-----BEGIN CERTIFICATE-----MIICDzCCAbWgAwIBAgIUJM+lFwR1WyaM0pOqgloNGsvTLjEwCgYIKoZIzj0EAwIwXTEqMCgGA1UEAwwhZmFrZS1lYy11bml0LXRlc3QuZXhhbXBsZS5pbnZhbGlkMSIwIAYDVQQKDBlFeGFtcGxlIFRlc3QgRml4dHVyZSBPbmx5MQswCQYDVQQGEwJYWDAeFw0yNjA1MTMxMzE5MjdaFw0zNjA1MTAxMzE5MjdaMF0xKjAoBgNVBAMMIWZha2UtZWMtdW5pdC10ZXN0LmV4YW1wbGUuaW52YWxpZDEiMCAGA1UECgwZRXhhbXBsZSBUZXN0IEZpeHR1cmUgT25seTELMAkGA1UEBhMCWFgwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASjD1l8BUS3K/y60I9tUn6njNT378eh6rtqWA9JUM4Rij4iIOnnRRjBuN0L+fF/ojNgakEG6Dc17z6C1t3oJjCSo1MwUTAdBgNVHQ4EFgQUIsiKjviTVSQMf52xWPlEu6+/cU0wHwYDVR0jBBgwFoAUIsiKjviTVSQMf52xWPlEu6+/cU0wDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNIADBFAiAWbprqeHYk2Ov262fzisXbyLmSlNa9uOMQXCaxmqm6tQIhAIgIr5jIokOT+ThCgant0Jr/XUEryzF7tpvAD/lJkwv9-----END CERTIFICATE-----"
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
        // notsecret
        val singleLineKey =
            "-----BEGIN PRIVATE KEY-----MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCcxRFWa06rNo5xsdpGTLsETLviFAR4wdB0bylr6lHCuNpdW1gM1TyRvVvFyQWbJK+Dk+emSV7ocbUibUaxdhWQ1W1Jv7L/s3H4zzYdWpOF4LZ+W0wHVhav4AZjiU7GbvO15uK2gbfuEZHJ06uLTpKMh5uWRGpEBr0eNDE1Y6au1lpZtJSfgXuJRXHd+kbngjtmjb4XcW/3xCBbcAcpmXSCGgE9uV5uuYVmCwYBrLtHK5MUKz0i1F85XN2DEQwAEHEkg5d9Z0ypxoKHMRGmBkoN2t9SihAU04efHHKWk2GTDFZGY4Ga4w/YmmhoPM54gU7ONRN3LYRfThr0Y5ivJfPTAgMBAAECggEACqQ97GCAXeg9fG5BjirAjybToiG7DqS+t7NMBoKeENpncurea+Xq+fb2odMRdFnl0sgEHio2LQ/QPIlaa8rDj/9M2d1kvdgP0SnlAdJs19ZMd6tO2o33dZzUEjGh4p++ygbli39RXZxdCcGP5Xbsmml3dZh99ibW85PrZd+2fYD9hsO0CTRdlCLB0/Gy/yKW4iujlJyp1HfPFeiw/lKL5GwNSFpMJElwGcQUPVdXPqU+GzPJH1m54jFlYzIZCXuHW4U99+NPFj6foA5PjMS3ZXcEyWZfhuHbDYrqj3aKxRURWaNdGVxML6xSmdXvN/4yo7CkLUr1PN0apKACVS0FYQKBgQDUTP0aTp+ja3TNVtrv4K1v8Me3stlbxR9zeB/zL4QBjSkALBhz8xeYGYm4i1elH3Ch9jn2rHy9E8C7zxwZbW2mYHtV/Micyc6X03yqBuEVzsqlIxSUoUKM4yVTlje5jj6ggo/OJP86wUExbvsxkjocjrRitqk5eAlt6KHr5SxbqQKBgQC9CewRCFBJQpYaHDEpyVHlsgM6qwP4W4VvStScTQ1hXnHE7g0mQhKxiS+WgF6RJkhiJhTvRfSVm0/3PSLa9woEtgiNx+cPscHLFvR0y4RCbjA1QDIGLbQV9/e6ntnlup4nFrCEgA17oQtb/EGXMAIRL2SdsGpd3YEWrSchOxuhGwKBgQDJeyt17Qo6OMAIJJbxswRGyXdxUm5QVtsLZgTEceLQ6hvwSukGGb3ZntsCZlPOpPDq9Nh7z6UueHGgi+U6CI1YqhZDO/1UN342vwKABrlVTgUqBgoBKK4VMXl6Q4UtN98dy+sYlCoZo9DwTkhc+k7mTVTKnlop7U7dnTsWuk+HyQKBgHOAm39wr/WDPMlpTlS00FhjIvv2v+9ApE/yzeNOZQ2IMkVcGia1GkzlgHEZsC5J0NI/aG0mNiIvCnYLIb/eT32/Z4yRhsmdF8aqGOU/8GjSgJwYxDfoNu9xWijppENsefNyNppOz24pYRJsF/tzdt/fMD/1KZh+ncAoPg9c2S3fAoGAWzYz9FFDIXv8yx8e5eGJstq+F2GkOrTliPfjX5PP1NkIJ8vFxGVE6RKzn8FSoE+Xxz5GjcULoE0hno7p2oYqLQpd7pI3LyLTSZhTN0FKDHQQpPtzoo6hSda53i3AaI0VO6mRi3VJaSoWhUkz/4ULR1NuuWpW2oFD2hIEQZqkiDI=-----END PRIVATE KEY-----"
        // when
        val privateKey = PemUtils.parsePrivateKey(singleLineKey)
        // then
        assertThat(privateKey).isNotNull()
        assertThat(privateKey.algorithm).isEqualTo("RSA")
    }

    @Test
    fun `#parseCertificate handles malformed PEM with only header newline`() {
        // given
        // notsecret
        val malformedPem =
            "-----BEGIN CERTIFICATE-----\nMIIDlTCCAn2gAwIBAgIUJ/MyNwdZC5vGYJMyYa5m4letZrYwDQYJKoZIhvcNAQELBQAwWjEnMCUGA1UEAwweZmFrZS11bml0LXRlc3QuZXhhbXBsZS5pbnZhbGlkMSIwIAYDVQQKDBlFeGFtcGxlIFRlc3QgRml4dHVyZSBPbmx5MQswCQYDVQQGEwJYWDAeFw0yNjA1MTMxMzE4MDJaFw0zNjA1MTAxMzE4MDJaMFoxJzAlBgNVBAMMHmZha2UtdW5pdC10ZXN0LmV4YW1wbGUuaW52YWxpZDEiMCAGA1UECgwZRXhhbXBsZSBUZXN0IEZpeHR1cmUgT25seTELMAkGA1UEBhMCWFgwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCG4CRbIkDOtpWzjWVW3V62FKzSfdAhdOJ/avqaPU2FiSjwEcBuVceoT5ilVjNWuDSqWeTrmwPjBfzywpB9OHrziqE5rRBnlyuxTMgxxbpNU8WEBFtn2RWvKen0uZOOLTro1oQsI6ALqKd07s8t9XjIZMEiOzhvKzYK6xQiqXjnYJqWAw3ZjhuvPcuvAALTXJMB6dASZNJ+q7gUd0gIMIjXVzAcj/QPxISwr3JMbpk+GvDnz0kFt7TFQRMqW56dbK36ukjDvLdFd+bbigE6m55vsGVdyZC55wBIB87ycn0zc3hgrfej4JVEqEhhlsifUkjGqNR2h9cdY3u58gzJwZP5AgMBAAGjUzBRMB0GA1UdDgQWBBSn488Oxr0rTEaI1Q3xHhxERrAZ5jAfBgNVHSMEGDAWgBSn488Oxr0rTEaI1Q3xHhxERrAZ5jAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQAu0fWReMMgSMM2ctyslZ/b00FDUnDq713HQ+HH3sB28NVxvwKUHR637Z/VzX2HNlR5wuR2ulxKi6m54EBVCuE+T4kwPD/wx32RtGMAyuBlpamLC6WOdmVIVdYr66BRE7KdfTNnK+MJAa0duD5KniqxkdMU7ZxveHM6RRv/hDg0qybOxLSwetmfI9CRiw0qOGiX5PhCqsJVIf1FxRl2mPPO0HiI94AyenmZfatuz9Y8Pb/q7cgdXpX2x29dnqXXO91qbVHk+zIIsYowqsdnMTfqNHFSJGrNovvI63/GQ/8148oKAALaH4VgNOyVIdaKkPDR5I/WBnNmgJHFa/ozYnVi-----END CERTIFICATE-----"
        // when
        val certificate = PemUtils.parseCertificate(malformedPem)
        // then
        assertThat(certificate).isNotNull()
        assertThat(certificate.subjectX500Principal.name).contains("CN=fake-unit-test.example.invalid")
    }
}
