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
import io.kubernetes.client.util.KubeConfig
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

object KubeConfigTlsUtils {

    fun findClusterByServer(
        serverUrl: String,
        kubeConfigs: List<KubeConfig>
    ): KubeConfigNamedCluster? =
        kubeConfigs
            .flatMap { it.clusters ?: emptyList() }
            .mapNotNull { KubeConfigNamedCluster.fromMap(it as Map<*, *>) }
            .firstOrNull { it.cluster.server == serverUrl }

    fun extractCaCertificates(
        namedCluster: KubeConfigNamedCluster
    ): List<X509Certificate> {
        val caData = namedCluster.cluster.certificateAuthorityData ?: return emptyList()
        val decoded = Base64.getDecoder().decode(caData)
        val factory = CertificateFactory.getInstance("X.509")

        return factory
            .generateCertificates(decoded.inputStream())
            .filterIsInstance<X509Certificate>()
    }
}
