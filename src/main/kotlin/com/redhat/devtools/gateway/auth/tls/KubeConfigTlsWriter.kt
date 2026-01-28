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

import com.redhat.devtools.gateway.kubeconfig.BlockStyleFilePersister
import com.redhat.devtools.gateway.kubeconfig.KubeConfigNamedCluster
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils.path
import com.redhat.devtools.gateway.openshift.Utils
import java.security.cert.X509Certificate

object KubeConfigTlsWriter {

    fun write(
        namedCluster: KubeConfigNamedCluster,
        certificates: List<X509Certificate>
    ) {
        if (certificates.isEmpty()) return

        val encodedCa = KubeConfigCertEncoder.encode(certificates.first())

        val allConfigs = KubeConfigUtils.getAllConfigs(
            KubeConfigUtils.getAllConfigFiles()
        )

        // Find the kubeconfig that actually contains this cluster
        val config = allConfigs.firstOrNull { kubeConfig ->
            kubeConfig.clusters?.any { entry ->
                val map = entry as? Map<*, *> ?: return@any false
                map["name"] == namedCluster.name
            } == true
        } ?: return

        val clusterEntry = config.clusters
            ?.firstOrNull { entry ->
                val map = entry as? Map<*, *> ?: return@firstOrNull false
                map["name"] == namedCluster.name
            }
                as? MutableMap<*, *>
            ?: return

        // Write certificate-authority-data
        Utils.setValue(
            clusterEntry,
            encodedCa,
            arrayOf("cluster", "certificate-authority-data")
        )

        // Remove insecure flag if present
        removeInsecureSkipTlsVerify(clusterEntry)

        // Persist
        val file = config.path?.toFile() ?: return
        val persister = BlockStyleFilePersister(file)
        persister.save(
            config.contexts,
            config.clusters,
            config.users,
            config.preferences,
            config.currentContext
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeInsecureSkipTlsVerify(clusterEntry: MutableMap<*, *>) {
        val clusterMap = clusterEntry["cluster"] as? MutableMap<*, *> ?: return
        (clusterMap as MutableMap<String, Any>).remove("insecure-skip-tls-verify")
    }
}
