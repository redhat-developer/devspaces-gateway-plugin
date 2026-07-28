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

import com.redhat.devtools.gateway.kubeconfig.KubeConfigNamedCluster
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

import kotlin.io.path.readText

object KubeConfigTlsUtils {

    fun extractCaCertificates(
        namedCluster: KubeConfigNamedCluster
    ): List<X509Certificate> =
        namedCluster.cluster.certificateAuthority
            ?.let(::extractCaCertificates)
            .orEmpty()

    fun extractCaCertificates(caSource: CertificateSource): List<X509Certificate> {
        val caContent = try {
            if (caSource.isFilePath) {
                caSource.toPath().readText()
            } else {
                Base64.getDecoder().decode(caSource.value).toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            return emptyList()
        }

        val factory = CertificateFactory.getInstance("X.509")

        return factory
            .generateCertificates(caContent.byteInputStream())
            .filterIsInstance<X509Certificate>()
    }
}
