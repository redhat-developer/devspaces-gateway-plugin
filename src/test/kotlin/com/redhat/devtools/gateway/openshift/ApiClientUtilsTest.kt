/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package com.redhat.devtools.gateway.openshift

import com.redhat.devtools.gateway.openshift.apiclient.ApiClientUtils
import io.kubernetes.client.openapi.ApiClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * [ApiClientUtils.cloneForExec] must not re-read [ApiClient.sslCaCert]: the Kubernetes client keeps a
 * single-use stream; [ApiClient.setSslCaCert] triggers [applySslSettings] which consumes it.
 * Exec cloning forks OkHttp from the parent and must not call those setters again.
 */
class ApiClientUtilsTest {

    /** Self-signed RSA fixture for this test only (*.invalid); not from any cluster or public CA. */
    // notsecret
    private val testCaPem = """
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

    @Test
    fun `cloneForExec succeeds when sslCaCert stream is already consumed`() {
        // given
        val caStream = ByteArrayInputStream(testCaPem.toByteArray(StandardCharsets.UTF_8))
        val client = ApiClient()
        client.basePath = "https://127.0.0.1:6443"
        client.setVerifyingSsl(true)
        client.setSslCaCert(caStream)

        assertThat(caStream.read())
            .describedAs("CA stream must be exhausted after first setSslCaCert / applySslSettings")
            .isEqualTo(-1)
        // when
        val execClient = ApiClientUtils.cloneForExec(client)
        // then
        assertThat(execClient).isNotNull
        assertThat(execClient.httpClient.sslSocketFactory)
            .isSameAs(client.httpClient.sslSocketFactory)
    }
}
