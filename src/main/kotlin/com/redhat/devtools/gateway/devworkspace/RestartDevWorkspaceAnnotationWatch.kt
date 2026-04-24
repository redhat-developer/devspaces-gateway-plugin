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

import com.intellij.openapi.diagnostic.thisLogger
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.Watch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Watches the DevWorkspace CR for [DevWorkspacePatch.RESTART_KEY] (set from the remote IDE).
 * When the annotation appears, deletes workspace pod(s) and closes the thin client so the user can reconnect.
 */
class RestartDevWorkspaceAnnotationWatch(
    private val onIsAnnotated: () -> Job,
    client: ApiClient,
    private val namespace: String,
    private val workspaceName: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val devWorkspaces = DevWorkspaces(client)
    private val restartAnnotationPending = AtomicBoolean(false)

    fun start(scope: CoroutineScope): Job {
        val fieldSelector = "metadata.name=$workspaceName"
        return scope.launch(dispatcher) {
            while (isActive) {
                val watcher = createWatcher(namespace, fieldSelector) ?: run {
                    delay(1000.milliseconds)
                    continue
                }
                try {
                    for (event in watcher) {
                        if (!isActive) break
                        if (event.type != "ADDED" && event.type != "MODIFIED") continue

                        val dw = runCatching { DevWorkspace.from(event.`object`) }.getOrNull() ?: continue
                        if (dw.name != workspaceName || dw.namespace != namespace) continue
                        val hasRestart = dw.annotations[DevWorkspacePatch.RESTART_KEY] == DevWorkspacePatch.RESTART_VALUE
                        if (!hasRestart) {
                            restartAnnotationPending.set(false)
                            continue
                        }
                        if (!restartAnnotationPending.compareAndSet(false, true)) continue

                        launch {
                            try {
                                thisLogger().debug(
                                    "$namespace/$workspaceName was annotated, invoking handler."
                                )
                                onIsAnnotated.invoke()
                            } catch (e: Exception) {
                                thisLogger().error("Restart annotation handling failed", e)
                                restartAnnotationPending.set(false)
                                return@launch
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Watch connection dropped; reconnect below.
                } finally {
                    runCatching { watcher.close() }
                }
                delay(100.milliseconds)
            }
        }
    }

    private fun createWatcher(namespace: String, fieldSelector: String): Watch<Any>? {
        return runCatching {
            devWorkspaces.createWatcher(namespace = namespace, fieldSelector = fieldSelector)
        }.getOrElse { e ->
            thisLogger().warn("Could not create watch for workspace in $namespace, retrying", e)
            null
        }
    }
}
