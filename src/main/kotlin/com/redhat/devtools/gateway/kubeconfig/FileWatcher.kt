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

import kotlinx.coroutines.*
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

class FileWatcher(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
) {
    private var onFileChanged: ((Path) -> Unit)? = null
    private val registeredDirectories = ConcurrentHashMap<WatchKey, Path>()
    private val monitoredFiles = ConcurrentHashMap.newKeySet<Path>()
    private val notifyLock = ReentrantLock()
    private var watchJob: Job? = null

    fun start() {
        this.watchJob = scope.launch(dispatcher) {
            pollDirectoryEvents()
        }
    }

    private suspend fun CoroutineScope.pollDirectoryEvents() {
        try {
            while (isActive) {
                val key = watchService.poll(100, TimeUnit.MILLISECONDS)
                if (key == null) {
                    @Suppress("ConvertLongToDuration")
                    delay(100)
                    continue
                }
                val dir = registeredDirectories[key] ?: continue
                handleDirectoryEvent(key, dir)
                key.reset()
            }
        } catch (_: ClosedWatchServiceException) {
            // Watch service was closed, exit gracefully
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        registeredDirectories.keys.forEach { it.cancel() }
        registeredDirectories.clear()
        monitoredFiles.clear()
        try {
            watchService.close()
        } catch (_: Exception) {
            // Ignore exceptions when closing
        }
    }

    /**
     * Registers [path] for watching and invokes the [onFileChanged] callback if this is
     * a new file that was not previously monitored. For non-existent files, the callback
     * is invoked so listeners can track them for a later CREATE event. If [path] is
     * already in the monitored set, no callback is issued — this avoids redundant refreshes.
     *
     * @param path the file to monitor; may be non-existent (e.g. a kubeconfig file expected
     *   to appear later). If [path.parent] is null, the path will not be registered.
     * @return this instance for chaining.
     */
    fun addFile(path: Path): FileWatcher {
        val parentDir = path.parent
        if (parentDir != null
            && !monitoredFiles.contains(path)) {
            registerDirectory(parentDir)
            monitoredFiles.add(path)
            invokeOnFileChanged(path)
        }
        return this
    }

    fun removeFile(path: Path): FileWatcher {
        if (!monitoredFiles.remove(path)) {
            return this
        }
        val parentDir = path.parent ?: return this
        if (monitoredFiles.none { it.parent == parentDir }) {
            unregisterDirectory(parentDir)
        }
        return this
    }

    fun getMonitoredFiles(): Set<Path> = monitoredFiles

    /** Parent directories that currently have an active [WatchKey]. For tests. */
    internal fun getWatchedDirectories(): Set<Path> = registeredDirectories.values.toSet()

    fun onFileChanged(action: ((Path) -> Unit)?) {
        notifyLock.lock()
        try {
            this.onFileChanged = action
        } finally {
            notifyLock.unlock()
        }
    }

    private fun registerDirectory(directory: Path) {
        if (registeredDirectories.values.any { it == directory }) {
            return
        }
        val watchKey = directory.register(
            watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE
        )
        registeredDirectories[watchKey] = directory
    }

    private fun unregisterDirectory(directory: Path) {
        val keys = registeredDirectories.filterValues { it == directory }.keys
        keys.forEach { key ->
            registeredDirectories.remove(key)
            key.cancel()
        }
    }

    private fun handleDirectoryEvent(key: WatchKey, dir: Path) {
        for (event in key.pollEvents()) {
            val relativePath = event.context() as? Path ?: continue
            val changedFile = dir.resolve(relativePath)
            val kind = event.kind()

            if (!monitoredFiles.contains(changedFile)
                || kind == StandardWatchEventKinds.OVERFLOW
            ) {
                continue
            }

            // DELETE: file is already gone — still notify so callers can refresh.
            // CREATE/MODIFY: only notify for an existing regular file.
            val shouldNotify = kind == StandardWatchEventKinds.ENTRY_DELETE
                || (changedFile.exists() && changedFile.isRegularFile())

            if (shouldNotify) {
                invokeOnFileChanged(changedFile)
            }
        }
    }

    private fun invokeOnFileChanged(path: Path) {
        notifyLock.lock()
        try {
            onFileChanged?.invoke(path)
        } finally {
            notifyLock.unlock()
        }
    }
}
