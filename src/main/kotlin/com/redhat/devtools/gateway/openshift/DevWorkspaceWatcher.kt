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
package com.redhat.devtools.gateway.openshift

import com.intellij.openapi.application.EDT
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.Watch
import kotlinx.coroutines.*

interface DevWorkspaceListener {
    fun onAdded(dw: DevWorkspace)
    fun onUpdated(dw: DevWorkspace)
    fun onDeleted(dw: DevWorkspace)
}

class DevWorkspaceWatcher(
    private val namespace: String,
    private val createWatcher: (namespace: String, latestResourceVersion: String?) -> Watch<Any>,
    private val createFilter: (String) -> ((DevWorkspace) -> Boolean),
    private val listener: DevWorkspaceListener,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start(latestResourceVersion: String? = null) {
        job = scope.launch {
            watchLoop(latestResourceVersion)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun watchLoop(latestResourceVersion: String? = null) {
        while (scope.isActive) {
            val watcher = createWatcher(namespace, latestResourceVersion)
            val matches = createFilter(namespace)

            try {
                for (event in watcher) {
                    if (!scope.isActive) break

                    val dw = DevWorkspace.from(event.`object`)
                    withContext(Dispatchers.EDT) {
                        when (event.type) {
                            "ADDED"    -> if(matches(dw)) listener.onAdded(dw)
                            "MODIFIED" -> if(matches(dw)) listener.onUpdated(dw) else listener.onDeleted(dw)
                            "DELETED"  -> listener.onDeleted(dw)
                        }
                    }
                }
            } catch (_: Exception) {
                // connection dropped or closed — reconnect
            } finally {
                watcher.close()
            }

            delay(100)
        }
    }
}

class DevWorkspaceWatchManager(
    private val client: ApiClient,
    private val createWatcher: (String, String?) -> Watch<Any>,
    private val createFilter: (String) -> ((DevWorkspace) -> Boolean),
    private val listener: DevWorkspaceListener,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val watchers = mutableListOf<DevWorkspaceWatcher>()

    fun start(lastResourceVersions: Map<String, String?>? = null) {
        Projects(client).list().onEach { project ->
            val ns = Utils.getValue(project, arrayOf("metadata","name")) as String
            val w = DevWorkspaceWatcher(
                namespace = ns,
                createWatcher = createWatcher,
                createFilter = createFilter,
                listener = listener,
                scope = scope
            )
            watchers += w
            w.start(lastResourceVersions?.get(ns))
        }
    }

    fun stop() {
        watchers.forEach { it.stop() }
        watchers.clear()
    }
}

