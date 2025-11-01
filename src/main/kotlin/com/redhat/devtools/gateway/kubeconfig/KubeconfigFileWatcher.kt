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

class KubeconfigFileWatcher(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
) {
    private var onFileChanged: ((Path) -> Unit)? = null
    private val registeredKeys = ConcurrentHashMap<WatchKey, Path>()
    private val monitoredFiles = ConcurrentHashMap<Path, Unit>()
    private var watchJob: Job? = null

    fun start() {
        watchJob = scope.launch(dispatcher) {
            while (isActive) {
                val key = watchService.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (key == null) {
                    delay(100)
                    continue
                }
                val dir = registeredKeys[key] ?: continue

                for (event in key.pollEvents()) {
                    val relativePath = event.context() as? Path ?: continue
                    val changedFile = dir.resolve(relativePath)

                    if (monitoredFiles.containsKey(changedFile)
                        && event.kind() != StandardWatchEventKinds.OVERFLOW) {
                        onFileChanged?.invoke(changedFile)
                    }
                }
                key.reset()
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        watchService.close()
    }

    fun addFile(path: Path) {
        if (!path.exists()
            || !path.isRegularFile()) {
            return
        }
        val parentDir = path.parent
        if (parentDir != null
            && !monitoredFiles.containsKey(path)) {
            val watchKey = parentDir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
            )
            registeredKeys[watchKey] = parentDir
            monitoredFiles[path] = Unit
            onFileChanged?.invoke(path)
        }
    }

    fun removeFile(path: Path) {
        monitoredFiles.remove(path)
    }

    fun onFileChanged(action: ((Path) -> Unit)?) {
        this.onFileChanged = action
    }
}
