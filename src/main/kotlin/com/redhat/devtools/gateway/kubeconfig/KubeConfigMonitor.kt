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
import com.redhat.devtools.gateway.openshift.Cluster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

class KubeConfigMonitor(
    private val scope: CoroutineScope,
    private val fileWatcher: FileWatcher,
    private val kubeConfigUtils: KubeConfigUtils
) {
    private val logger = thisLogger<KubeConfigMonitor>()

    private val _clusters = MutableSharedFlow<List<Cluster>>(replay = 1)
    private val clusters = _clusters.asSharedFlow()

    private val monitoredPaths = mutableSetOf<Path>()

    /**
     * Runs the given action for each collected cluster.
     */
    suspend fun onClustersCollected(action: suspend (clusters: List<Cluster>) -> Unit) {
        logger.info("Setting up SharedFlow collection for cluster updates")
        clusters.collect { collected ->
            logger.info("Found ${collected.size} clusters")
            action(collected)
        }
    }

    /**
     * Returns the current clusters. For testing purposes only.
     *
     * @see [onClustersCollected]
     */
    internal fun getCurrentClusters(): List<Cluster> = _clusters.replayCache.firstOrNull() ?: emptyList()

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
    }

    internal fun updateMonitoredPaths() {
        val newPaths = mutableSetOf<Path>()
        newPaths.addAll(kubeConfigUtils.getAllConfigs())
        stopWatchingRemoved(newPaths)
        startWatchingNew(newPaths)

        monitoredPaths.clear()
        monitoredPaths.addAll(newPaths)
        logger.info("Monitored paths: $monitoredPaths")
    }

    private fun stopWatchingRemoved(newPaths: Set<Path>) {
        (monitoredPaths - newPaths).forEach { path ->
            fileWatcher.removeFile(path)
            logger.info("Stopped monitoring kubeconfig file: $path")
        }
    }

    private fun startWatchingNew(newPaths: Set<Path>) {
        (newPaths - monitoredPaths).forEach { path ->
            fileWatcher.addFile(path)
            logger.info("Started monitoring kubeconfig file: $path")
        }
    }

    internal fun refreshClusters() {
        logger.info("Reparsing kubeconfig files. Monitored paths: $monitoredPaths")
        val allClusters = kubeConfigUtils.getClusters(monitoredPaths.toList())
        scope.launch {
            logger.info("Emitting ${allClusters.size} clusters to SharedFlow")
            _clusters.emit(allClusters)
        }
        logger.info("Reparsed kubeconfig files. Found ${allClusters.size} clusters: ${allClusters.map { "${it.name}@${it.url}" }}")
    }

    fun onFileChanged(filePath: Path) {
        logger.info("Kubeconfig file changed: $filePath. Reparsing and updating clusters.")
        refreshClusters()
    }
}
