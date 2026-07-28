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

import com.redhat.devtools.gateway.kubeconfig.KubeConfigCluster
import com.redhat.devtools.gateway.kubeconfig.KubeConfigNamedCluster
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

class KubeConfigTlsUtilsTest {

    @Test
    fun `#extractCaCertificates parses certificate-authority-data`() {
        val source = TlsTestCertificates.caSourceFromData()

        val certificates = KubeConfigTlsUtils.extractCaCertificates(source)

        assertThat(certificates).hasSize(1)
        assertThat(certificates.first().serialNumber)
            .isEqualTo(TlsTestCertificates.caCertificate().serialNumber)
    }

    @Test
    fun `#extractCaCertificates reads certificate-authority file path`() {
        val tempFile = Files.createTempFile("test-ca", ".pem")
        tempFile.toFile().writeText(TlsTestCertificates.CA_PEM)
        val source = CertificateSource.fromPath(tempFile.toString())

        val certificates = KubeConfigTlsUtils.extractCaCertificates(source)

        assertThat(certificates).hasSize(1)
        assertThat(certificates.first().subjectX500Principal.name)
            .contains("CN=fake-unit-test.example.invalid")
    }

    @Test
    fun `#extractCaCertificates returns empty list for invalid data`() {
        val source = CertificateSource.fromData("not-a-valid-cert") // notsecret

        val certificates = KubeConfigTlsUtils.extractCaCertificates(source)

        assertThat(certificates).isEmpty()
    }

    @Test
    fun `#extractCaCertificates delegates from named cluster`() {
        val namedCluster = KubeConfigNamedCluster(
            name = "test",
            cluster = KubeConfigCluster(
                server = "https://api.example.com:6443",
                certificateAuthority = TlsTestCertificates.caSourceFromData(),
            ),
        )

        val certificates = KubeConfigTlsUtils.extractCaCertificates(namedCluster)

        assertThat(certificates).hasSize(1)
        assertThat(certificates.first().serialNumber)
            .isEqualTo(TlsTestCertificates.caCertificate().serialNumber)
    }
}
