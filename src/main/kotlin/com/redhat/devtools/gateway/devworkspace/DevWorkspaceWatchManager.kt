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
import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.openshift.isForbidden
import com.redhat.devtools.gateway.openshift.isGone
import com.redhat.devtools.gateway.openshift.isNotFound
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1Status
import io.kubernetes.client.util.Watch
import kotlinx.coroutines.*

/**
 * Result of a fresh LIST performed to resume a watch after its resource version
 * expired (HTTP 410 Gone). Produced by a throwing list ([DevWorkspaces.listForWatchResume]);
 * failures never reach here — [DevWorkspaceWatch.relistAndReconcile] catches them and
 * skips [DevWorkspaceListener.onReset].
 */
internal data class DevWorkspaceRelistResult(
    val items: List<DevWorkspace>,
    val resourceVersion: String?
)

internal class DevWorkspaceWatchManager(
    private val createWatch: (String, String?) -> Watch<Any>,
    private val relist: (String) -> DevWorkspaceRelistResult,
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
        val toStart = lastResourceVersions
            .filterValues { it != null }
            .map { (ns, resourceVersion) ->
                DevWorkspaceWatch(
                    namespace = ns,
                    createWatcher = createWatch,
                    relist = relist,
                    listener = listener,
                    scope = scope
                ) to resourceVersion
            }
        watchers += toStart.map { (watch, _) -> watch }
        toStart.forEach { (watch, resourceVersion) -> watch.start(resourceVersion) }
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

    /**
     * Called after a watch's resource version expired and the namespace was relisted
     * successfully. [items] is the authoritative, current set of DevWorkspaces for
     * [namespace]; the listener must reconcile its view against it (add/update/remove
     * as needed). Not called when the relist itself failed, so listeners can safely
     * treat [items] as ground truth.
     */
    fun onReset(namespace: String, items: List<DevWorkspace>)
}

internal class DevWorkspaceWatch(
    private val namespace: String,
    private val createWatcher: (namespace: String, latestResourceVersion: String?) -> Watch<Any>,
    private val relist: (namespace: String) -> DevWorkspaceRelistResult,
    private val listener: DevWorkspaceListener,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    @Volatile
    private var stopped = false
    @Volatile
    private var currentWatcher: Watch<Any>? = null

    fun start(latestResourceVersion: String? = null) {
        thisLogger().debug("DevWorkspace watch for namespace '$namespace' starting from resourceVersion=$latestResourceVersion")
        stopped = false
        job = scope.launch {
            watchLoop(latestResourceVersion)
        }
    }

    fun stop() {
        thisLogger().debug("DevWorkspace watch for namespace '$namespace' stopping")
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
        // Tracks the resource version of the last event actually consumed, so a
        // routine reconnect (the apiserver closes watch streams periodically — this
        // is normal, not an error) resumes from where we left off instead of from
        // the stale version the watch was originally started with.
        var currentResourceVersion = latestResourceVersion
        while (scope.isActive && !stopped) {
            try {
                val watcher = createWatcher(namespace, currentResourceVersion)
                currentWatcher = watcher
                watcher.use { watcher ->
                    for (event in watcher) {
                        if (!scope.isActive || stopped) break

                        if (event.type == "ERROR") {
                            // Watch.parseLine() deserializes ERROR events into a V1Status and
                            // resolves the more specific Response(String, V1Status) constructor,
                            // which sets `object` to null and the payload lands in `status` instead.
                            val status = event.status
                            if (status.isResourceVersionExpired()) {
                                thisLogger().info(
                                    "DevWorkspace watch for namespace '$namespace' received 410/Expired " +
                                            "(resourceVersion=$currentResourceVersion); relisting to resume."
                                )
                                currentResourceVersion = relistAndReconcile()
                            } else {
                                thisLogger().warn(
                                    "DevWorkspace watch for namespace '$namespace' received ERROR event: " +
                                            "code=${status?.code} reason=${status?.reason} message=${status?.message}"
                                )
                            }
                            break // stream is ending/unusable after an ERROR event — reconnect
                        }

                        val dw = DevWorkspace.from(event.`object`)
                        thisLogger().debug(
                            "DevWorkspace watch for namespace '$namespace': ${event.type} " +
                                    "${dw.name} phase=${dw.phase} resourceVersion=${dw.resourceVersion}"
                        )
                        // Track progress through the stream independently of whether dispatching
                        // to the listener succeeds, so a transient UI-dispatch failure can't make
                        // the next reconnect resume from a stale resource version.
                        dispatchToListener {
                            when (event.type) {
                                "ADDED"    -> listener.onAdded(dw)
                                "MODIFIED" -> listener.onUpdated(dw)
                                "DELETED"  -> listener.onDeleted(dw)
                            }
                        }
                        dw.resourceVersion?.let { currentResourceVersion = it }
                    }
                    // connection dropped or closed — reconnect from currentResourceVersion
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.isForbidden() || e.isNotFound()) {
                    // don't retry, user cannot watch this namespace/resource.
                    thisLogger().warn(
                        "DevWorkspace watch for namespace '$namespace' stopped permanently: " +
                                "${e.code} ${e.message}"
                    )
                    stopped = true
                    return
                }
                if (e.isGone()) {
                    thisLogger().info(
                        "DevWorkspace watch for namespace '$namespace' got 410 Gone on reconnect " +
                                "(resourceVersion=$currentResourceVersion); relisting to resume."
                    )
                    currentResourceVersion = relistAndReconcile()
                } else {
                    thisLogger().warn(
                        "DevWorkspace watch for namespace '$namespace' Kubernetes API error ${e.code}; retrying.",
                        e
                    )
                }
            } catch (e: Exception) {
                thisLogger().debug(
                    "DevWorkspace watch for namespace '$namespace' connection dropped; reconnecting.",
                    e
                )
            } finally {
                currentWatcher = null
            }

            @Suppress("ConvertLongToDuration")
            delay(100)
        }
    }

    /**
     * Performs a fresh LIST for [namespace], reconciles the listener's view via
     * [DevWorkspaceListener.onReset], and returns the resource version to resume
     * watching from (or `null` if the LIST itself failed, so the next loop iteration
     * retries). A failure while notifying the listener does not discard the fresh
     * resource version — the watch must still resume from it. Callers must supply a
     * throwing list (see [DevWorkspaces.listForWatchResume]); swallowed empty results
     * must not be passed in, or [onReset] would wipe the table.
     */
    private suspend fun relistAndReconcile(): String? {
        val result = try {
            relist(namespace)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            thisLogger().warn(
                "DevWorkspace watch for namespace '$namespace' relist after 410 failed; will retry.",
                e
            )
            return null
        }
        dispatchToListener { listener.onReset(namespace, result.items) }
        thisLogger().info(
            "DevWorkspace watch for namespace '$namespace' resumed after relist: " +
                    "${result.items.size} workspaces, resourceVersion=${result.resourceVersion}"
        )
        return result.resourceVersion
    }

    /**
     * Dispatches [block] to the listener on the EDT, under any modality. Failures are
     * logged but never propagated — a UI-dispatch problem must not be mistaken for a
     * watch/relist failure by the caller.
     */
    private suspend fun dispatchToListener(block: () -> Unit) {
        try {
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                if (!stopped) block()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            thisLogger().warn(
                "DevWorkspace watch for namespace '$namespace' failed to dispatch an update to the listener.",
                e
            )
        }
    }

    private fun V1Status?.isResourceVersionExpired(): Boolean =
        this?.code == 410 ||
                this?.reason.equals("Expired", ignoreCase = true) ||
                this?.reason.equals("Gone", ignoreCase = true)
}