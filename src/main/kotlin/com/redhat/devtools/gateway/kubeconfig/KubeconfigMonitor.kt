/*
 * Copyright (c) 2025 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.kubeconfig

import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.openshift.kube.Cluster
import com.redhat.devtools.gateway.openshift.kube.KubeConfigUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

class KubeconfigMonitor(
    private val scope: CoroutineScope,
    private val fileWatcher: KubeconfigFileWatcher,
    private val kubeConfigBuilder: KubeConfigUtils
) {
    private val logger = thisLogger<KubeconfigMonitor>()

    private val clusters = MutableStateFlow<List<Cluster>>(emptyList())

    private val monitoredPaths = mutableSetOf<Path>()

    /**
     * Runs the given action for each collected cluster.
     */
    suspend fun onClusterCollected(action: suspend (clusters: List<Cluster>) -> Unit) {
        clusters.collect(action)
    }

    /**
     * Returns the current clusters. For testing purposes only.
     *
     * @see [onClusterCollected]
     */
    internal fun getCurrentClusters(): List<Cluster> = clusters.value

    fun start() {
        fileWatcher.onFileChanged(::onFileChanged)
        scope.launch {
            fileWatcher.start()
        }
        updateMonitoredPaths()
        refreshClusters()
    }

    fun stop() {
        fileWatcher.stop()
        fileWatcher.onFileChanged(null)
        scope.cancel()
    }

    internal fun updateMonitoredPaths() {
        val newPaths = mutableSetOf<Path>()
        newPaths.addAll(kubeConfigBuilder.getAllConfigs())
        watchNewPaths(newPaths)
        stopWatchingRemovedPaths(newPaths)

        monitoredPaths.clear()
        monitoredPaths.addAll(newPaths)
        logger.info("Currently monitoring paths: $monitoredPaths")
    }

    private fun stopWatchingRemovedPaths(newPaths: MutableSet<Path>) {
        (monitoredPaths - newPaths).forEach { path ->
            fileWatcher.removeFile(path)
            logger.info("Stopped monitoring kubeconfig file: $path")
        }
    }

    private fun watchNewPaths(newPaths: MutableSet<Path>) {
        (newPaths - monitoredPaths).forEach { path ->
            fileWatcher.addFile(path)
            logger.info("Started monitoring kubeconfig file: $path")
        }
    }

    internal fun refreshClusters() {
        logger.info("Reparsing kubeconfig files. Monitored paths: $monitoredPaths")
        val allClusters = kubeConfigBuilder.getClusters(monitoredPaths.toList())
        clusters.value = allClusters
        logger.info("Reparsed kubeconfig files. Found ${allClusters.size} clusters: ${allClusters.map { "${it.name}@${it.apiServerUrl}" }}")
    }

    fun onFileChanged(filePath: Path) {
        logger.info("Kubeconfig file changed: $filePath. Reparsing and updating clusters.")
        refreshClusters()
    }
}
