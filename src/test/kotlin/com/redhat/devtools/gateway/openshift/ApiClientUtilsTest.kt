/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package com.redhat.devtools.gateway.openshift

import com.redhat.devtools.gateway.auth.tls.TlsTestCertificates
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

    /** Self-signed RSA fixture from [TlsTestCertificates]; not from any cluster or public CA. */
    private val testCaPem = TlsTestCertificates.CA_PEM

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
