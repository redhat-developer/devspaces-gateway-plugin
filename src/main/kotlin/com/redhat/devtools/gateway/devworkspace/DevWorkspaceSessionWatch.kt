/*
 * Copyright (c) 2026 Red Hat, Inc.
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
import com.redhat.devtools.gateway.openshift.WatchEventType
import com.redhat.devtools.gateway.openshift.isGone
import com.redhat.devtools.gateway.openshift.isNotFound
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.util.Watch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches the DevWorkspace backing a thin-client session; reports the two actions that end it:
 *
 * - **stop**: workspace stopped, stopping, or deleted. Reported once; session closes client
 *   sockets, Guest reads EOF without waiting for pod termination.
 * - **restart**: restart annotation set by the remote IDE. Reported once per absent-to-present
 *   transition; re-armed only after annotation removal. The reconnect triggered by the restart
 *   starts a new watch, so later requests are still observed.
 *
 * Stop wins over annotation. Annotation is state, not request: it persists during the restart and
 * survives failure or abandonment. Presence alone must not restart a workspace the user stopped.
 * Only absent-to-present is a request. Annotation present at watch start ([readWorkspace]) is a
 * leftover: removed ([clearRestartAnnotation]) so later requests are observable transitions. If
 * the workspace is already stopped, stop is reported instead and the annotation is ignored.
 *
 * Both policies share one object, selector, stream, reconnection, and teardown.
 *
 * @param readWorkspace Reads the workspace before opening the stream and after resume-version
 *   expiry: supplies the resume version and the restart-annotation baseline.
 * @param createWatcher Opens the event stream at the given `resourceVersion`; nothing missed
 *   during stream opening.
 * @param clearRestartAnnotation Removes a leftover restart annotation present at watch start.
 *   Not a request (transition never observed); cleanup keeps later requests observable, otherwise
 *   it would block every later request.
 */
@Suppress("TooManyFunctions", "LongParameterList") // one watch stream shared by two small policies
internal class DevWorkspaceSessionWatch(
    private val namespace: String,
    private val workspaceName: String,
    private val readWorkspace: () -> DevWorkspace,
    private val createWatcher: (latestResourceVersion: String?) -> Watch<Any>,
    private val onRestartAnnotated: (() -> Unit)?,
    private val onStopped: () -> Unit,
    private val clearRestartAnnotation: (() -> Unit)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        private const val RETRY_DELAY: Long = 1000
    }

    private val stopReported = AtomicBoolean(false)

    /** Whether the annotation was present on the last event consumed; guards the restart edge. */
    private val restartSeen = AtomicBoolean(false)

    /** The version to resume the stream at; advanced by every event consumed, never backwards. */
    private var watchResourceVersion: String? = null

    /** Set when the API server reports [watchResourceVersion] as expired, so it must be re-read. */
    private var isResumeVersionExpired = false

    private var isBaselineRead = false

    @Volatile
    private var currentWatcher: Watch<Any>? = null

    private var job: Job? = null

    fun start(scope: CoroutineScope): Job =
        scope
            .launch(dispatcher) { watchUntilStopped() }
            .also { this.job = it }

    /**
     * Stops watching. Closing the current watch unblocks the thread waiting for the next event
     * instead of leaving it until the API server ends the stream.
     */
    fun stop() {
        stopReported.set(true)
        val watcher = currentWatcher
        currentWatcher = null
        runCatching { watcher?.close() }
        job?.cancel()
    }

    private suspend fun watchUntilStopped() {
        while (currentCoroutineContext().isActive
            && !stopReported.get()) {
            val stopped = watchOnce()
            if (!stopped) {
                @Suppress("ConvertLongToDuration")
                delay(RETRY_DELAY)
            }
        }
    }

    /**
     * Consumes one watch stream. Returns `true` when the stream reported the workspace stop, so the
     * caller does not open another watch.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // failures only mean "reopen"
    private suspend fun watchOnce(): Boolean {
        if (isResumeVersionExpired) {
            if (!reconcileExpiredResumeVersion()) return false
            if (stopReported.get()) return true
        }
        // a baseline (or 404) stop already reported: end the loop instead of retrying after a delay
        val watcher = openWatcher()
            ?: return stopReported.get()
        return try {
            consumeUntilStop(watcher)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Watch.next() throws RuntimeException on any transport failure: reopen the watch
            noteExpiredResumeVersion(e)
            thisLogger().debug(
                "Watch for DevWorkspace $namespace/$workspaceName ended; reopening.",
                e
            )
            false
        } finally {
            currentWatcher = null
            runCatching { watcher.close() }
        }
    }

    /**
     * Re-reads the workspace when the resume version expired (HTTP 410, e.g. after a network outage
     * longer than the etcd compaction window), so the stream can continue from a fresh version.
     * Applies what changed during the gap like an event would: a stop ends the session, an
     * annotation that appeared meanwhile is a request. Returns `false` when the read failed, so the
     * next attempt retries.
     */
    private fun reconcileExpiredResumeVersion(): Boolean {
        val workspace = readWorkspaceOrNull() ?: return false
        isResumeVersionExpired = false
        watchResourceVersion = workspace.resourceVersion
        if (workspace.isNoLongerRunning) {
            reportStopped()
        } else {
            handleRestartAnnotation(workspace)
        }
        return true
    }

    private fun consumeUntilStop(watcher: Watch<Any>): Boolean {
        var stopped = false
        for (event in watcher) {
            stopped = handleEvent(event)
            if (stopped) break
        }
        if (stopped) {
            reportStopped()
        }
        return stopped
    }

    /**
     * Consumes one watch event: drops events for other resources and stale versions, advances
     * [watchResourceVersion], then applies the event's effect. A stop is reported as `true` and
     * takes precedence over the restart annotation; otherwise the annotation edge is handled and
     * `false` is returned.
     *
     * @param event The watch event received from the API server; ignored when it is not a
     *   supported event type, not this workspace, or not newer than the last consumed version.
     * @return `true` when the event reports that the workspace no longer runs, so the caller must
     *   not open another watch; `false` otherwise.
     */
    @Suppress("ReturnCount")
    private fun handleEvent(event: Watch.Response<Any>): Boolean {
        val type = WatchEventType.fromWireName(event.type)
            ?: return false
        val workspace = watchedWorkspace(event, type, workspaceName, namespace)
            ?: return false
        if (isStale(workspace.resourceVersion)) {
            return false
        }
        saveResourceVersion(workspace)
        // A stop wins over the annotation: while the workspace is down an annotation on it can
        // only be a leftover of an earlier restart request, never a request to restart it again.
        if (isStopEvent(type, workspace)) {
            return true
        }
        handleRestartAnnotation(workspace)
        return false
    }

    /**
     * Invokes [onRestartAnnotated] when the annotation appears. An annotation that was already
     * present ([restartSeen], seeded from the baseline) is state, not a new request.
     */
    private fun handleRestartAnnotation(workspace: DevWorkspace) {
        val onRestartHandler = onRestartAnnotated ?: return
        if (!workspace.isAnnotatedForRestart) {
            restartSeen.set(false)
        } else if (restartSeen.compareAndSet(false, true)) {
            // inline: non-blocking handler, failure surfaces for retry
            runRestartHandler(onRestartHandler)
        }
    }

    @Suppress("TooGenericExceptionCaught") // handler failure only means "retry on the next event"
    private fun runRestartHandler(handler: () -> Unit) {
        try {
            handler()
        } catch (e: Exception) {
            thisLogger().error(
                "Restart annotation handling failed for $namespace/$workspaceName.",
                e
            )
            restartSeen.set(false)
        }
    }

    /**
     * Saves [DevWorkspace.resourceVersion] as the last consumed version. Stale events are dropped
     * before this is called, so the saved version never moves backwards.
     */
    private fun saveResourceVersion(workspace: DevWorkspace) {
        workspace.resourceVersion?.let { watchResourceVersion = it }
    }

    /**
     * Whether [eventResourceVersion] is not newer than the last event consumed. Events arrive in
     * order within one stream, but a reconnect or an API replica with a lagging watch cache can
     * redeliver an older state - which must not resurrect an annotation that was already handled.
     * Versions that are missing or not plain integers are accepted, never treated as stale.
     */
    private fun isStale(eventResourceVersion: String?): Boolean {
        val eventVersion = eventResourceVersion?.toLongOrNull()
        val currentVersion = watchResourceVersion?.toLongOrNull()
        return eventVersion != null && currentVersion != null && eventVersion <= currentVersion
    }

    /**
     * Opens the watch, or returns `null` when the API call failed (the caller retries) or the
     * baseline already reported the stop, which ends the loop.
     */
    @Suppress("TooGenericExceptionCaught") // any API/transport failure only means "retry"
    private fun openWatcher(): Watch<Any>? {
        if (!readBaseline()) return null
        return try {
            createWatcher(watchResourceVersion)
                .also { currentWatcher = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // any failure (API error, transport) means "watch unavailable": retried by the loop
            noteExpiredResumeVersion(e)
            thisLogger().debug(
                "Could not watch DevWorkspace $namespace/$workspaceName; retrying.",
                e
            )
            null
        }
    }

    /**
     * Reads the workspace once, before the first stream, to seed the resume version and the
     * restart annotation to measure the request edge against. Returns `true` once available; a
     * failure leaves the watch unopened (retried) rather than guessing a baseline.
     *
     * A workspace that is already stopped or stopping ends the session here and returns `false`:
     * the stop happened before the stream opened, so no event would ever redeliver it.
     */
    @Suppress("ReturnCount") // false on read failure and false on an already-stopped baseline
    private fun readBaseline(): Boolean {
        if (!isBaselineRead) {
            val workspace = readWorkspaceOrNull() ?: return false
            watchResourceVersion = workspace.resourceVersion
            isBaselineRead = true
            if (workspace.isNoLongerRunning) {
                restartSeen.set(workspace.isAnnotatedForRestart)
                reportStopped()
                return false // loop ends on stopReported; no stream is needed
            }
            restartSeen.set(workspace.isAnnotatedForRestart)
            if (workspace.isAnnotatedForRestart) {
                clearStaleAnnotation()
            }
        }
        return true
    }

    /**
     * Removes a restart annotation that predates this watch, so that a request made now is an
     * absent-to-present transition again. A failure leaves the annotation in place: it is then
     * still ignored, which is no worse than not cleaning it up.
     */
    @Suppress("TooGenericExceptionCaught") // cleanup is best effort; the watch must keep running
    private fun clearStaleAnnotation() {
        val clear = clearRestartAnnotation ?: return
        try {
            clear()
            // The annotation is gone server-side now, whether or not the watch stream delivers the
            // resulting event. Reset here so the next appearance is a request even when the event
            // is missed (for example because the stream is reconnecting).
            restartSeen.set(false)
            thisLogger().info("Removed stale restart annotation from $namespace/$workspaceName.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            thisLogger().warn(
                "Could not remove stale restart annotation from $namespace/$workspaceName.",
                e
            )
        }
    }

    /**
     * Reads the current workspace, or `null` when the API call failed (the caller retries). A
     * workspace that no longer exists is reported as stopped here: no stream will ever end the
     * session for it.
     */
    @Suppress("TooGenericExceptionCaught") // any API/transport failure only means "retry"
    private fun readWorkspaceOrNull(): DevWorkspace? {
        return try {
            readWorkspace()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.isNotFound()) {
                reportStopped()
            }
            thisLogger().debug(
                "Could not read DevWorkspace $namespace/$workspaceName; retrying.",
                e
            )
            null
        } catch (e: Exception) {
            thisLogger().debug(
                "Could not read DevWorkspace $namespace/$workspaceName; retrying.",
                e
            )
            null
        }
    }

    /**
     * Records that the API server no longer knows the resume version (HTTP 410 Gone): the next
     * attempt must re-read the workspace instead of resuming from it.
     */
    private fun noteExpiredResumeVersion(e: Exception) {
        if (e is ApiException && e.isGone()) {
            isResumeVersionExpired = true
        }
    }

    private fun reportStopped() {
        if (!stopReported.compareAndSet(false, true)) return
        thisLogger().info(
            "DevWorkspace $namespace/$workspaceName is stopped or stopping; " +
                "reporting the lost connection without waiting for the pod."
        )
        runCatching { onStopped() }
            .onFailure { e ->
                thisLogger().warn(
                    "Could not report the stopped DevWorkspace $namespace/$workspaceName.",
                    e
                )
            }
    }
}

private val EVENT_TYPES = setOf(WatchEventType.ADDED, WatchEventType.MODIFIED, WatchEventType.DELETED)

/** The event's workspace when it is the one called [workspaceName] in [namespace], else `null`. */
private fun watchedWorkspace(
    event: Watch.Response<Any>,
    type: WatchEventType,
    workspaceName: String,
    namespace: String
): DevWorkspace? {
    if (type !in EVENT_TYPES) return null
    return toWorkspace(event.`object`)
        ?.takeIf { it.name == workspaceName && it.namespace == namespace }
}

/** Parses a watch event payload, or returns `null` when it is not a DevWorkspace. */
private fun toWorkspace(value: Any?): DevWorkspace? =
    runCatching { DevWorkspace.from(value) }.getOrNull()

private fun isStopEvent(type: WatchEventType, workspace: DevWorkspace): Boolean =
    type == WatchEventType.DELETED || workspace.isNoLongerRunning
