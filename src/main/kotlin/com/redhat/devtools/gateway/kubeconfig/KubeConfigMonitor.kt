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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

class KubeConfigMonitor(
    private val scope: CoroutineScope,
    private val fileWatcher: FileWatcher,
    private val kubeConfigUtils: KubeConfigUtils
) {
    private val logger = thisLogger<KubeConfigMonitor>()

    /** null until the first successful refresh — avoids pushing an empty list to late collectors. */
    private val _clusters = MutableStateFlow<List<Cluster>?>(null)
    private val clusters = _clusters.asStateFlow()

    private val refreshMutex = Mutex()
    private val refreshGeneration = AtomicLong(0)

    /**
     * Runs the given action for each collected cluster list.
     */
    suspend fun onClustersCollected(action: suspend (clusters: List<Cluster>) -> Unit) {
        logger.info("Setting up StateFlow collection for cluster updates")
        clusters.filterNotNull().collect { collected ->
            logger.info("Found ${collected.size} clusters")
            action(collected)
        }
    }

    /**
     * Returns the current clusters. For testing purposes only.
     *
     * @see [onClustersCollected]
     */
    internal fun getCurrentClusters(): List<Cluster> = _clusters.value.orEmpty()

    fun start() {
        fileWatcher.onFileChanged(::onFileChanged)
        scope.launch {
            fileWatcher.start()
        }
        updateMonitoredPaths()
        // Barrier refresh: ensures a generation after all sync addFile() notifies.
        refreshClusters()
    }

    fun stop() {
        fileWatcher.stop()
        fileWatcher.onFileChanged(null)
    }

    internal fun updateMonitoredPaths() {
        val newPaths = kubeConfigUtils.getAllConfigFiles().toSet()
        stopWatchingRemoved(newPaths)
        startWatchingNew(newPaths)
        logger.info("Monitored paths: ${fileWatcher.getMonitoredFiles()}")
    }

    private fun stopWatchingRemoved(newPaths: Set<Path>) {
        (fileWatcher.getMonitoredFiles() - newPaths).forEach { path ->
            fileWatcher.removeFile(path)
            logger.info("Stopped monitoring kubeconfig file: $path")
        }
    }

    private fun startWatchingNew(newPaths: Set<Path>) {
        (newPaths - fileWatcher.getMonitoredFiles()).forEach { path ->
            fileWatcher.addFile(path)
            logger.info("Started monitoring kubeconfig file: $path")
        }
    }

    /**
     * Schedules a cluster refresh. Multiple rapid calls (e.g. per-file [FileWatcher.addFile]
     * notifies during bootstrap) coalesce: only the latest generation parses and emits.
     * Paths are snapshotted when the work runs, not when it is scheduled, so a burst of
     * sync registrations yields one full-list emit.
     *
     * [MutableStateFlow] skips collector notifications when the new list equals the previous.
     */
    internal fun refreshClusters() {
        val gen = refreshGeneration.incrementAndGet()
        scope.launch {
            refreshMutex.withLock {
                if (gen != refreshGeneration.get()) {
                    return@withLock
                }
                val monitored = fileWatcher.getMonitoredFiles().toList()
                logger.info("Reparsing kubeconfig files. Monitored paths: $monitored")
                val allClusters = kubeConfigUtils.getClusters(monitored)
                if (gen != refreshGeneration.get()) {
                    return@withLock
                }
                val previous = _clusters.value
                _clusters.value = allClusters
                if (previous == allClusters) {
                    logger.info("Skipping notify; clusters unchanged (${allClusters.size})")
                } else {
                    logger.info(
                        "Updated clusters (${allClusters.size}): " +
                            allClusters.map { "${it.name}@${it.url}" }
                    )
                }
            }
        }
    }

    fun onFileChanged(filePath: Path) {
        logger.info("Kubeconfig file changed: $filePath. Reparsing and updating clusters.")
        refreshClusters()
    }
}
