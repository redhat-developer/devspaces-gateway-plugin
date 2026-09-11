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
package com.redhat.devtools.gateway.devworkspace

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.util.Watch
import kotlinx.coroutines.*

internal class DevWorkspaceWatchManager(
    private val createWatch: (String, String?) -> Watch<Any>,
    private val createFilter: (String) -> ((DevWorkspace) -> Boolean),
    private val listener: DevWorkspaceListener,
    private val scope: CoroutineScope
) {
    private val watchers = mutableListOf<DevWorkspaceWatch>()

    /**
     * Starts a watch for each namespace in [lastResourceVersions].
     *
     * This is a replace-all operation: any previously started watches are
     * stopped first via [stop] before the new ones are created, so calling
     * this method repeatedly does not accumulate duplicate watchers.
     *
     * @param lastResourceVersions Maps a namespace to the resource version to resume
     * its watch from. Entries with a `null` resource version are skipped.
     */
    fun start(lastResourceVersions: Map<String, String?> = emptyMap()) {
        // Idempotent: never accumulate duplicate watchers per namespace.
        stop()
        lastResourceVersions.forEach { (ns, resourceVersion) ->
            if (resourceVersion == null) {
                return@forEach
            }
            val w = DevWorkspaceWatch(
                namespace = ns,
                createWatcher = createWatch,
                createFilter = createFilter,
                listener = listener,
                scope = scope
            )
            watchers += w
            w.start(resourceVersion)
        }
    }

    fun stop() {
        watchers.forEach { it.stop() }
        watchers.clear()
    }
}

interface DevWorkspaceListener {
    fun onAdded(dw: DevWorkspace)
    fun onUpdated(dw: DevWorkspace)
    fun onDeleted(dw: DevWorkspace)
}

internal class DevWorkspaceWatch(
    private val namespace: String,
    private val createWatcher: (namespace: String, latestResourceVersion: String?) -> Watch<Any>,
    private val createFilter: (String) -> ((DevWorkspace) -> Boolean),
    private val listener: DevWorkspaceListener,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    @Volatile
    private var stopped = false
    @Volatile
    private var currentWatcher: Watch<Any>? = null

    fun start(latestResourceVersion: String? = null) {
        stopped = false
        job = scope.launch {
            watchLoop(latestResourceVersion)
        }
    }

    fun stop() {
        stopped = true
        // Closing the stream unblocks a thread stuck in Watch.hasNext() immediately
        // instead of waiting for the OkHttp read timeout.
        try {
            currentWatcher?.close()
        } catch (_: Exception) {
            // best effort
        }
        currentWatcher = null
        job?.cancel()
        job = null
    }

    private suspend fun watchLoop(latestResourceVersion: String? = null) {
        while (scope.isActive && !stopped) {
            try {
                val watcher = createWatcher(namespace, latestResourceVersion)
                currentWatcher = watcher
                watcher.use { watcher ->
                    var matches = createFilter(namespace)
                    for (event in watcher) {
                        if (!scope.isActive || stopped) break

                        val dw = DevWorkspace.from(event.`object`)
                        if (event.type == "ADDED") {
                            matches = createFilter(namespace)
                        }
                        withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                            if (stopped) return@withContext
                            when (event.type) {
                                "ADDED"    -> if(matches(dw)) listener.onAdded(dw)
                                "MODIFIED" -> if (matches(dw)) listener.onUpdated(dw)
                                "DELETED"  -> listener.onDeleted(dw)
                            }
                        }
                    }
                    // connection dropped or closed — reconnect
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.code == 403 || e.code == 404) {
                    // don't retry, user cannot watch this namespace/resource.
                    stopped = true
                    return
                }
                // Other Kubernetes API errors — retry.
            } catch (_: Exception) {
                // Connection dropped or closed — reconnect.
            } finally {
                currentWatcher = null
            }

            @Suppress("ConvertLongToDuration")
            delay(100)
        }
    }
}