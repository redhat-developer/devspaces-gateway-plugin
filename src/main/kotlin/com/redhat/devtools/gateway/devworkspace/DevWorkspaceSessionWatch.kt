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
 * leftover: removed ([removeRestartAnnotations]) so later requests are observable transitions. If
 * the workspace is already stopped, stop is reported instead and the annotation is ignored.
 *
 * The stream resumes from the baseline version, so it also replays the update that removed the
 * leftover annotations and any still-annotated update made while the cleanup was in flight. Those
 * are state, not requests: [cleanupBoundaryVersion] marks the last cleanup write, and annotated
 * states at or before it are ignored. Stop events are never filtered by that boundary.
 *
 * Both policies share one object, selector, stream, reconnection, and teardown.
 *
 * @param readWorkspace Reads the workspace before opening every stream: supplies the resume
 *   version and reconciles the restart-annotation state after a missed event or reconnect.
 * @param createWatcher Opens the event stream at the given `resourceVersion`; nothing missed
 *   during stream opening.
 * @param removeRestartAnnotations Removes the leftover restart request annotations
 *   ([DevWorkspace.RESTART_KEY] and [DevWorkspace.PENDING_TEMPLATE_KEY]) present at watch start.
 *   Not a request (transition never observed); cleanup keeps later requests observable, otherwise
 *   it would block every later request. Returns the resource version produced by the last cleanup
 *   write, or `null` when no write was needed, so replay of the cleaned-up state can be ignored.
 */
@Suppress("TooManyFunctions", "LongParameterList") // one watch stream shared by two small policies
internal class DevWorkspaceSessionWatch(
    private val namespace: String,
    private val workspaceName: String,
    private val readWorkspace: () -> DevWorkspace,
    private val createWatcher: (latestResourceVersion: String?) -> Watch<Any>,
    private val onRestartAnnotated: (() -> Unit)?,
    private val onStopped: () -> Unit,
    private val removeRestartAnnotations: (() -> String?)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        private const val RETRY_DELAY: Long = 1000
        private const val CLEAR_ATTEMPTS: Int = 3
    }

    private val stopReported = AtomicBoolean(false)

    /** Whether the annotation was present on the last event consumed; guards the restart edge. */
    private val restartSeen = AtomicBoolean(false)

    /**
     * Resource version of the last write that removed the leftover annotations. Annotated states
     * at or before it are replays of the cleaned-up state, not new requests. `null` until a
     * cleanup write reports its version.
     */
    @Volatile
    private var cleanupBoundaryVersion: String? = null

    /** The version to resume the stream at; advanced by every event consumed, never backwards. */
    private var watchResourceVersion: String? = null

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
    @Suppress("TooGenericExceptionCaught") // failures only mean "reopen"
    private suspend fun watchOnce(): Boolean {
        // a baseline (or 404) stop already reported: end the loop instead of retrying after a delay
        val watcher = openWatcher()
            ?: return stopReported.get()
        return try {
            consumeUntilStop(watcher)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Watch.next() throws RuntimeException on any transport failure: reopen the watch
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

    private fun consumeUntilStop(watcher: Watch<Any>): Boolean {
        if (consumeEvents(watcher)) {
            reportStopped()
            return true
        }
        return false
    }

    /**
     * @return `true` when an event reported the workspace stop
     */
    private fun consumeEvents(watcher: Watch<Any>): Boolean {
        for (event in watcher) {
            // An ERROR event (e.g. 410 Expired) ends the stream. Reopening re-reads the workspace
            // and resumes from its current version instead of looping on the expired one.
            if (WatchEventType.fromWireName(event.type) == WatchEventType.ERROR) break
            if (handleEvent(event)) return true
        }
        return false
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
     * present ([restartSeen], seeded from the baseline) is state, not a new request. So is an
     * annotated state replayed from at or before [cleanupBoundaryVersion]: the cleanup that
     * removed the leftover annotation, or an update made while it was in flight, produced it.
     */
    private fun handleRestartAnnotation(workspace: DevWorkspace) {
        val onRestartHandler = onRestartAnnotated ?: return
        if (!workspace.isAnnotatedForRestart) {
            restartSeen.set(false)
        } else if (isReplayedCleanupState(workspace)) {
            // Keep restartSeen disarmed: the next annotated state after the boundary is a request.
            thisLogger().debug(
                "Ignoring replayed restart annotation for $namespace/$workspaceName at " +
                    "resource version ${workspace.resourceVersion}; cleanup boundary is " +
                    "$cleanupBoundaryVersion."
            )
        } else if (restartSeen.compareAndSet(false, true)) {
            thisLogger().info("Restart annotation observed for $namespace/$workspaceName; requesting restart.")
            // inline: non-blocking handler, failure surfaces for retry
            runRestartHandler(onRestartHandler)
        }
    }

    /**
     * Whether [workspace] is still the annotated state the baseline cleanup consumed. The watch
     * resumes from the baseline version, so it redelivers that state (and any annotated update
     * between the baseline read and the last cleanup write). Missing or non-numeric versions
     * cannot be classified and are not suppressed.
     */
    private fun isReplayedCleanupState(workspace: DevWorkspace): Boolean {
        val eventVersion = workspace.resourceVersion?.toLongOrNull()
        val boundaryVersion = cleanupBoundaryVersion?.toLongOrNull()
        return eventVersion != null
                && boundaryVersion != null
                && eventVersion <= boundaryVersion
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
     * Reads the workspace, reconciles the state that matters for this session, and opens the watch
     * from that fresh version.
     *
     * The read is repeated before every stream, not only before the first one. A watch stream may
     * drop an event (or the server may advance the object's resourceVersion without redelivering
     * the event of the annotation being added). Resuming from the last consumed version then
     * skips that request forever, because the annotation already is state on the object. Re-reading
     * makes every reconnect compare the actual state against [restartSeen], so a request made while
     * the stream was down is still observed.
     *
     * On the first read the annotation present is a leftover from before this session: it is
     * removed ([clearStaleAnnotation]) instead of restarting.
     *
     * Returns `null` when the read failed (the caller retries) or when the workspace no longer runs
     * (the stop was reported and ends the loop).
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // every failure only means "retry"; null ends the attempt
    private suspend fun openWatcher(): Watch<Any>? {
        val workspace = readWorkspaceOrNull() ?: return null
        watchResourceVersion = workspace.resourceVersion
        if (workspace.isNoLongerRunning) {
            restartSeen.set(workspace.isAnnotatedForRestart)
            reportStopped()
            return null
        }
        if (!isBaselineRead) {
            isBaselineRead = true
            restartSeen.set(workspace.isAnnotatedForRestart)
            // remove pending template (even without restart annotation). Must not survive into a later request
            if (workspace.isAnnotatedForRestart
                || workspace.pendingTemplatePayload != null) {
                clearStaleAnnotation()
            }
        } else {
            handleRestartAnnotation(workspace)
        }
        return try {
            createWatcher(watchResourceVersion)
                .also { currentWatcher = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // any failure (API error, transport) means "watch unavailable": retried by the loop
            thisLogger().debug(
                "Could not watch DevWorkspace $namespace/$workspaceName; retrying.",
                e
            )
            null
        }
    }

    /**
     * Removes the restart request annotations that predate this watch — both
     * [DevWorkspace.RESTART_KEY] and [DevWorkspace.PENDING_TEMPLATE_KEY] — so that a request made
     * now is an absent-to-present transition again. Cleanup also runs when only the pending
     * template is left, so a partially failed cleanup cannot linger into a later request.
     *
     * The version of the last successful write becomes [cleanupBoundaryVersion]. The watch stream
     * resumes from the baseline version, so it redelivers the still-annotated state between the
     * baseline read and that write. Without the boundary, such a replay would look like a new
     * request and restart the workspace without user action.
     *
     * Known limitation: a request published before the baseline read is part of the state that
     * read returned, indistinguishable from a leftover, and is cleared with it. Only a request
     * published after the cleanup boundary is observed as an absent-to-present transition; the
     * annotations carry no request identity to tell the two apart.
     *
     * Cleanup failures are retried; if the restart annotation is still present afterwards the
     * baseline is re-read on a later watch reopen instead of blocking every later request forever.
     */
    @Suppress("TooGenericExceptionCaught") // cleanup is best effort; the watch must keep running
    private suspend fun clearStaleAnnotation() {
        val clear = removeRestartAnnotations ?: return
        repeat(CLEAR_ATTEMPTS) { attempt ->
            try {
                val clearedVersion = clear()
                // Both annotations are gone server-side now, whether or not the watch stream
                // delivers the resulting event. Reset here so the next appearance is a request
                // even when the event is missed (for example because the stream is reconnecting).
                restartSeen.set(false)
                recordCleanupBoundary(clearedVersion)
                thisLogger().info("Removed stale restart request annotations from $namespace/$workspaceName.")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn(
                    "Could not remove stale restart request annotations from $namespace/$workspaceName.",
                    e
                )
                if (attempt < CLEAR_ATTEMPTS - 1) {
                    @Suppress("ConvertLongToDuration")
                    delay(RETRY_DELAY)
                }
            }
        }
        // The removal kept failing: re-read the server state instead of assuming the stale
        // annotation is still there. If it is gone, the next appearance is a request again.
        rearmAfterFailedCleanup()
    }

    /**
     * Re-reads the workspace after every cleanup attempt failed. If the annotation is gone, the
     * re-armed state is the new boundary so a replay cannot be mistaken for a request; if it
     * remains, the baseline is re-read on a later reopen to retry the cleanup.
     */
    private fun rearmAfterFailedCleanup() {
        val workspace = readWorkspaceOrNull()
        if (workspace == null || workspace.isAnnotatedForRestart) {
            isBaselineRead = false // retry the cleanup when the watch is reopened
            return
        }
        restartSeen.set(false)
        recordCleanupBoundary(workspace.resourceVersion)
    }

    /** Advances [cleanupBoundaryVersion] to [version] when it is newer; never moves it backwards. */
    private fun recordCleanupBoundary(version: String?) {
        val candidate = version?.toLongOrNull() ?: return
        val current = cleanupBoundaryVersion?.toLongOrNull()
        if (current == null || candidate > current) {
            cleanupBoundaryVersion = version
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
