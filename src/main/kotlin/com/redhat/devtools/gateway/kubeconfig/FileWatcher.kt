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
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

class FileWatcher(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
) {
    private var onFileChanged: ((Path) -> Unit)? = null
    private val registeredKeys = ConcurrentHashMap<WatchKey, Path>()
    private val monitoredFiles = ConcurrentHashMap.newKeySet<Path>()
    private var watchJob: Job? = null

    fun start() {
        this.watchJob = scope.launch(dispatcher) {
            try {
                while (isActive) {
                    val key = watchService.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (key == null) {
                        delay(100)
                        continue
                    }
                    val dir = registeredKeys[key] ?: continue
                    pollEvents(key, dir)
                    key.reset()
                }
            } catch (e: ClosedWatchServiceException) {
                // Watch service was closed, exit gracefully
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        try {
            watchService.close()
        } catch (e: Exception) {
            // Ignore exceptions when closing
        }
    }

    fun addFile(path: Path): FileWatcher {
        if (!path.exists()
            || !path.isRegularFile()) {
            return this
        }
        val parentDir = path.parent
        if (parentDir != null
            && !monitoredFiles.contains(path)) {
            val watchKey = parentDir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
            )
            registeredKeys[watchKey] = parentDir
            monitoredFiles.add(path)
            onFileChanged?.invoke(path)
        }
        return this
    }

    fun removeFile(path: Path): FileWatcher {
        monitoredFiles.remove(path)
        return this
    }

    fun onFileChanged(action: ((Path) -> Unit)?) {
        this.onFileChanged = action
    }

    private fun pollEvents(key: WatchKey, dir: Path) {
        for (event in key.pollEvents()) {
            val relativePath = event.context() as? Path ?: continue
            val changedFile = dir.resolve(relativePath)

            if (monitoredFiles.contains(changedFile)
                && event.kind() != StandardWatchEventKinds.OVERFLOW
            ) {
                onFileChanged?.invoke(changedFile)
            }
        }
    }

}
