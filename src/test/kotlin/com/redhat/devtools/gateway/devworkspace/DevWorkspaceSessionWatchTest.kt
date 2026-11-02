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

import io.kubernetes.client.util.Watch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class DevWorkspaceSessionWatchTest {

    private val namespace = "test-namespace"
    private val workspaceName = "test-workspace"

    private val stoppedReports = AtomicInteger()
    private val restartReports = AtomicInteger()
    private val clearReports = AtomicInteger()

    @BeforeEach
    fun beforeEach() {
        stoppedReports.set(0)
        restartReports.set(0)
        clearReports.set(0)
    }

    // --- stop policy -------------------------------------------------------

    @Test
    fun `reports a workspace that was stopped from the dashboard or gateway`(): Unit = runBlocking {
        // spec.started=false is what both stop entry points patch
        val watch = sessionWatch(event("MODIFIED", started = false))

        withTimeout(TIMEOUT) {
            watch.start(this).join() // completes after reporting the stop
        }

        assertThat(stoppedReports.get()).isEqualTo(1)
    }

    @Test
    fun `reports a workspace that is stopping`(): Unit = runBlocking {
        val watch = sessionWatch(event("MODIFIED", started = true, phase = DevWorkspace.STOPPING))

        withTimeout(TIMEOUT) {
            watch.start(this).join()
        }

        assertThat(stoppedReports.get()).isEqualTo(1)
    }

    @Test
    fun `reports a deleted workspace`(): Unit = runBlocking {
        val watch = sessionWatch(event("DELETED"))

        withTimeout(TIMEOUT) {
            watch.start(this).join()
        }

        assertThat(stoppedReports.get()).isEqualTo(1)
    }

    @Test
    fun `reports a workspace that was already stopped when the watch started`(): Unit = runBlocking {
        // the stop happened before the stream opened: no event would ever redeliver it
        val opened = AtomicInteger()
        val watch = DevWorkspaceSessionWatch(
            namespace = namespace,
            workspaceName = workspaceName,
            readWorkspace = { DevWorkspace.from(workspace(started = false)) },
            createWatcher = {
                opened.incrementAndGet()
                watcherOf(emptyList())
            },
            onRestartAnnotated = null,
            onStopped = { stoppedReports.incrementAndGet() },
            dispatcher = Dispatchers.Default
        )

        // well under the 1s retry delay: a reported stop must end the loop, not wait it out
        withTimeout(STOP_TIMEOUT) {
            watch.start(this).join()
        }

        assertThat(stoppedReports.get()).isEqualTo(1)
        assertThat(opened.get()).isZero() // nothing left to watch for a stopped workspace
    }

    @Test
    fun `reports a workspace that was already stopping when the watch started`(): Unit = runBlocking {
        val watch = sessionWatch(baselinePhase = DevWorkspace.STOPPING)

        withTimeout(STOP_TIMEOUT) {
            watch.start(this).join()
        }

        assertThat(stoppedReports.get()).isEqualTo(1)
    }

    @Test
    fun `does not report a running workspace`(): Unit = runBlocking {
        val watch = sessionWatch(event("MODIFIED"), event("MODIFIED"))

        val job = watch.start(this)
        delay(SETTLE)
        watch.stop()
        job.join()

        assertThat(stoppedReports.get()).isZero()
    }

    @Test
    fun `does not report a stopped workspace of another namespace`(): Unit = runBlocking {
        val watch = sessionWatch(event("MODIFIED", started = false, namespace = "other-namespace"))

        val job = watch.start(this)
        delay(SETTLE)
        watch.stop()
        job.join()

        assertThat(stoppedReports.get()).isZero()
    }

    @Test
    fun `does not report a stopped workspace with another name`(): Unit = runBlocking {
        val watch = sessionWatch(event("MODIFIED", started = false, name = "other-workspace"))

        val job = watch.start(this)
        delay(SETTLE)
        watch.stop()
        job.join()

        assertThat(stoppedReports.get()).isZero()
    }

    @Test
    fun `stops watching once the stop was reported`(): Unit = runBlocking {
        val opened = AtomicInteger()
        val watch = DevWorkspaceSessionWatch(
            namespace = namespace,
            workspaceName = workspaceName,
            readWorkspace = { DevWorkspace.from(workspace()) },
            createWatcher = {
                opened.incrementAndGet()
                watcherOf(listOf(event("MODIFIED", started = false)))
            },
            onRestartAnnotated = null,
            onStopped = { stoppedReports.incrementAndGet() },
            dispatcher = Dispatchers.Default
        )

        withTimeout(TIMEOUT) {
            watch.start(this).join()
        }

        assertThat(stoppedReports.get()).isEqualTo(1)
        assertThat(opened.get()).isEqualTo(1) // no reconnect after reporting
    }

    // --- restart policy ----------------------------------------------------

    @Test
    fun `reports a restart annotation`(): Unit = runBlocking {
        val watch = sessionWatch(event("MODIFIED", restart = true))

        val job = watch.start(this)
        awaitReport(restartReports, 1)
        watch.stop()
        job.join()

        assertThat(restartReports.get()).isEqualTo(1)
        assertThat(stoppedReports.get()).isZero()
    }

    @Test
    fun `does not report a restart annotation whose value is not true`(): Unit = runBlocking {
        val watch = sessionWatch(event("MODIFIED", restart = "false"))

        val job = watch.start(this)
        delay(SETTLE)
        watch.stop()
        job.join()

        assertThat(restartReports.get()).isZero()
    }

    @Test
    fun `reports a restart annotation only once until it is removed`(): Unit = runBlocking {
        // two annotated events back to back, then the annotation is removed and set again
        val watch = sessionWatch(
            event("MODIFIED", restart = true),
            event("MODIFIED", restart = true),
            event("MODIFIED"),
            event("MODIFIED", restart = true)
        )

        val job = watch.start(this)
        awaitReport(restartReports, 2)
        watch.stop()
        job.join()

        assertThat(restartReports.get()).isEqualTo(2)
    }

    @Test
    fun `does not report a restart annotation of a deleted workspace`(): Unit = runBlocking {
        val watch = sessionWatch(event("DELETED", restart = true))

        val job = watch.start(this)
        delay(SETTLE)
        watch.stop()
        job.join()

        assertThat(restartReports.get()).isZero()
        assertThat(stoppedReports.get()).isEqualTo(1) // deletion still ends the session
    }

    @Test
    fun `reports the stop that the restart itself causes`(): Unit = runBlocking {
        // the restart closes the thin client first, so this is at worst a duplicate signal
        val watch = sessionWatch(
            event("MODIFIED", restart = true),
            event("MODIFIED", started = false, restart = true)
        )

        val job = watch.start(this)
        awaitReport(restartReports, 1)
        withTimeout(TIMEOUT) {
            job.join()
        }

        assertThat(restartReports.get()).isEqualTo(1)
        assertThat(stoppedReports.get()).isEqualTo(1)
    }

    @Test
    fun `reports a stop even though the restart annotation is still present`(): Unit = runBlocking {
        // A leftover annotation is state, not a request: it must not turn a user stop into a restart
        val watch = sessionWatch(
            event("MODIFIED", started = false, restart = true),
            baselineRestart = true
        )

        withTimeout(TIMEOUT) {
            watch.start(this).join()
        }

        assertThat(stoppedReports.get()).isEqualTo(1)
        assertThat(restartReports.get()).isZero()
    }

    @Test
    fun `clears the annotation that was already present when the watch started`(): Unit = runBlocking {
        // a leftover annotation is state, not a request: it must be removed so the next request
        // is observable as an absent-to-present transition
        val watch = sessionWatch(baselineRestart = true)

        val job = watch.start(this)
        awaitReport(clearReports, 1)
        watch.stop()
        job.join()

        assertThat(clearReports.get()).isEqualTo(1)
        assertThat(restartReports.get()).isZero()
    }

    @Test
    fun `does not clear an annotation that was absent when the watch started`(): Unit = runBlocking {
        val watch = sessionWatch()

        val job = watch.start(this)
        delay(SETTLE)
        watch.stop()
        job.join()

        assertThat(clearReports.get()).isZero()
    }

    @Test
    fun `clears a pending template that was left without the restart annotation`(): Unit =
        runBlocking {
            // A cleanup that partially failed can leave the template behind. Applying it to a
            // later request would overwrite devfile changes made in between, so it is stale state
            // too and must be removed before a new request is accepted.
            val watch = sessionWatch(baselinePendingTemplate = """{"version":1,"template":{}}""")

            val job = watch.start(this)
            awaitReport(clearReports, 1)
            watch.stop()
            job.join()

            assertThat(clearReports.get()).isEqualTo(1)
            assertThat(restartReports.get()).isZero()
        }

    @Test
    fun `reports the annotation that is set again after the stale one was removed`(): Unit = runBlocking {
        val watch = sessionWatch(
            event("MODIFIED"),
            event("MODIFIED", restart = true),
            baselineRestart = true
        )

        val job = watch.start(this)
        awaitReport(restartReports, 1)
        watch.stop()
        job.join()

        assertThat(restartReports.get()).isEqualTo(1)
    }

    @Test
    fun `reports an annotation set after a stale one was cleaned without an absent event`(): Unit = runBlocking {
        // The watch stream may be reconnecting and thus not deliver the event of the cleanup. The
        // next annotated state must still be a request.
        val watch = sessionWatch(
            event("MODIFIED", restart = true),
            baselineRestart = true
        )

        val job = watch.start(this)
        awaitReport(restartReports, 1)
        watch.stop()
        job.join()

        assertThat(restartReports.get()).isEqualTo(1)
    }

    @Test
    fun `reports a restart requested while the stream was down`(): Unit = runBlocking {
        // an event can be missed while the watch is reconnecting; re-reading the workspace when the
        // stream is reopened must still observe the request
        val reads = AtomicInteger()
        val watch = DevWorkspaceSessionWatch(
            namespace = namespace,
            workspaceName = workspaceName,
            readWorkspace = {
                val restart = if (reads.incrementAndGet() > 1) true else null
                DevWorkspace.from(workspace(restart = restart))
            },
            createWatcher = { watcherOf(emptyList()) },
            onRestartAnnotated = { restartReports.incrementAndGet() },
            onStopped = { stoppedReports.incrementAndGet() },
            removeRestartAnnotations = {
                clearReports.incrementAndGet()
                null
            },
            dispatcher = Dispatchers.Default
        )

        val job = watch.start(this)
        awaitReport(restartReports, 1)
        watch.stop()
        job.join()

        assertThat(restartReports.get()).isEqualTo(1)
    }

    @Test
    fun `retries stale cleanup and still reports the next request`(): Unit = runBlocking {
        val clearAttempts = AtomicInteger()
        val watch = sessionWatch(
            event("MODIFIED", restart = true),
            baselineRestart = true,
            removeRestartAnnotations = {
                if (clearAttempts.incrementAndGet() == 1) {
                    error("cleanup failed")
                }
                null
            }
        )

        val job = watch.start(this)
        awaitReport(restartReports, 1)
        watch.stop()
        job.join()

        assertThat(clearAttempts.get()).isGreaterThanOrEqualTo(2)
        assertThat(restartReports.get()).isEqualTo(1)
    }

    @Test
    fun `ignores a stale redelivery of an annotation that was already handled`(): Unit = runBlocking {
        // an API replica with a lagging watch cache redelivers the annotated state after the removal
        val watch = sessionWatch(
            event("MODIFIED", restart = true, resourceVersion = "101"),
            event("MODIFIED", resourceVersion = "102"),
            event("MODIFIED", restart = true, resourceVersion = "101"),
            baselineResourceVersion = "100"
        )

        val job = watch.start(this)
        awaitReport(restartReports, 1)
        delay(SETTLE)
        watch.stop()
        job.join()

        assertThat(restartReports.get()).isEqualTo(1)
    }

    @Test
    fun `does not replay the still annotated state removed by the baseline cleanup`(): Unit = runBlocking {
        // cleanup removes the pending template first, so the stream resumed at the baseline
        // redelivers a version that still carries the restart annotation before the removal
        val watch = sessionWatch(
            event("MODIFIED", restart = true, resourceVersion = "101"),
            event("MODIFIED", resourceVersion = "102"),
            baselineRestart = true,
            baselineResourceVersion = "100",
            cleanupResourceVersion = "102"
        )

        val job = watch.start(this)
        delay(SETTLE)
        watch.stop()
        job.join()

        assertThat(restartReports.get()).isZero()
        assertThat(stoppedReports.get()).isZero()
    }

    @Test
    fun `reports a request made after the cleanup boundary`(): Unit = runBlocking {
        val watch = sessionWatch(
            event("MODIFIED", restart = true, resourceVersion = "101"), // cleanup replay
            event("MODIFIED", resourceVersion = "102"), // cleanup completed
            event("MODIFIED", restart = true, resourceVersion = "103"), // new request
            baselineRestart = true,
            baselineResourceVersion = "100",
            cleanupResourceVersion = "102"
        )

        val job = watch.start(this)
        awaitReport(restartReports, 1)
        watch.stop()
        job.join()

        assertThat(restartReports.get()).isEqualTo(1)
    }

    @Test
    fun `reports a stop even when it predates the cleanup boundary`(): Unit = runBlocking {
        val watch = sessionWatch(
            event("MODIFIED", started = false, restart = true, resourceVersion = "101"),
            baselineRestart = true,
            baselineResourceVersion = "100",
            cleanupResourceVersion = "102"
        )

        withTimeout(TIMEOUT) {
            watch.start(this).join()
        }

        assertThat(stoppedReports.get()).isEqualTo(1)
        assertThat(restartReports.get()).isZero()
    }

    @Test
    fun `does not replay an annotated reconnect baseline at the cleanup boundary`(): Unit =
        runBlocking {
            val reads = AtomicInteger()
            val watch = DevWorkspaceSessionWatch(
                namespace = namespace,
                workspaceName = workspaceName,
                readWorkspace = {
                    val resourceVersion = if (reads.incrementAndGet() == 1) "100" else "101"
                    DevWorkspace.from(
                        workspace(restart = true, resourceVersion = resourceVersion)
                    )
                },
                createWatcher = { watcherOf(emptyList()) },
                onRestartAnnotated = { restartReports.incrementAndGet() },
                onStopped = { stoppedReports.incrementAndGet() },
                removeRestartAnnotations = {
                    clearReports.incrementAndGet()
                    "102"
                },
                dispatcher = Dispatchers.Default
            )

            val job = watch.start(this)
            withTimeout(TIMEOUT) {
                while (reads.get() < 2) {
                    delay(10)
                }
            }
            delay(SETTLE)
            watch.stop()
            job.join()

            assertThat(restartReports.get()).isZero()
        }

    @Test
    fun `reports an annotated reconnect baseline after the cleanup boundary`(): Unit =
        runBlocking {
            val reads = AtomicInteger()
            val watch = DevWorkspaceSessionWatch(
                namespace = namespace,
                workspaceName = workspaceName,
                readWorkspace = {
                    val resourceVersion = if (reads.incrementAndGet() == 1) "100" else "103"
                    DevWorkspace.from(
                        workspace(restart = true, resourceVersion = resourceVersion)
                    )
                },
                createWatcher = { watcherOf(emptyList()) },
                onRestartAnnotated = { restartReports.incrementAndGet() },
                onStopped = { stoppedReports.incrementAndGet() },
                removeRestartAnnotations = {
                    clearReports.incrementAndGet()
                    "102"
                },
                dispatcher = Dispatchers.Default
            )

            val job = watch.start(this)
            awaitReport(restartReports, 1)
            watch.stop()
            job.join()

            assertThat(restartReports.get()).isEqualTo(1)
        }

    @Test
    fun `reports a stop when restart handling is disabled`(): Unit = runBlocking {
        // the connection started by a restart does not handle restarts itself
        val watch = DevWorkspaceSessionWatch(
            namespace = namespace,
            workspaceName = workspaceName,
            readWorkspace = { DevWorkspace.from(workspace()) },
            createWatcher = { watcherOf(listOf(event("MODIFIED", started = false, restart = true))) },
            onRestartAnnotated = null,
            onStopped = { stoppedReports.incrementAndGet() },
            dispatcher = Dispatchers.Default
        )

        withTimeout(TIMEOUT) {
            watch.start(this).join()
        }

        assertThat(stoppedReports.get()).isEqualTo(1)
        assertThat(restartReports.get()).isZero()
    }

    // --- helpers -----------------------------------------------------------

    @Suppress("LongParameterList") // mirrors the watch constructor and test variations
    private fun sessionWatch(
        vararg events: Watch.Response<Any>,
        baselineRestart: Any? = null,
        baselinePendingTemplate: String? = null,
        baselineResourceVersion: String? = null,
        baselineStarted: Boolean = true,
        baselinePhase: String = DevWorkspace.RUNNING,
        cleanupResourceVersion: String? = null,
        removeRestartAnnotations: () -> String? = {
            clearReports.incrementAndGet()
            cleanupResourceVersion
        }
    ) = DevWorkspaceSessionWatch(
        namespace = namespace,
        workspaceName = workspaceName,
        readWorkspace = {
            DevWorkspace.from(
                workspace(
                    started = baselineStarted,
                    phase = baselinePhase,
                    restart = baselineRestart,
                    pendingTemplate = baselinePendingTemplate,
                    resourceVersion = baselineResourceVersion
                )
            )
        },
        createWatcher = { watcherOf(events.toList()) },
        onRestartAnnotated = { restartReports.incrementAndGet() },
        onStopped = { stoppedReports.incrementAndGet() },
        removeRestartAnnotations = removeRestartAnnotations,
        dispatcher = Dispatchers.Default
    )

    private suspend fun awaitReport(counter: AtomicInteger, expected: Int) {
        withTimeout(TIMEOUT) {
            while (counter.get() < expected) {
                delay(10)
            }
        }
    }

    private fun watcherOf(events: List<Watch.Response<Any>>): Watch<Any> = FakeWatch(events)

    @Suppress("LongParameterList") // mirrors the fields of a DevWorkspace payload
    private fun event(
        type: String,
        started: Boolean = true,
        phase: String = DevWorkspace.RUNNING,
        restart: Any? = null,
        pendingTemplate: String? = null,
        name: String = workspaceName,
        namespace: String = this.namespace,
        resourceVersion: String? = null
    ): Watch.Response<Any> = Watch.Response(
        type,
        workspace(
            started = started,
            phase = phase,
            restart = restart,
            pendingTemplate = pendingTemplate,
            name = name,
            namespace = namespace,
            resourceVersion = resourceVersion
        )
    )

    @Suppress("LongParameterList") // mirrors the fields of a DevWorkspace payload
    private fun workspace(
        started: Boolean = true,
        phase: String = DevWorkspace.RUNNING,
        restart: Any? = null,
        pendingTemplate: String? = null,
        name: String = workspaceName,
        namespace: String = this.namespace,
        resourceVersion: String? = null
    ): Map<String, Any?> = mapOf(
        "metadata" to mapOf(
            "name" to name,
            "namespace" to namespace,
            "uid" to "uid",
            "annotations" to requestAnnotations(restart, pendingTemplate),
            "resourceVersion" to resourceVersion
        ),
        "spec" to mapOf(
            "started" to started
        ),
        "status" to mapOf(
            "phase" to phase
        )
    )

    private fun requestAnnotations(restart: Any?, pendingTemplate: String?): Map<String, String> =
        buildMap {
            if (restart != null) {
                put(DevWorkspace.RESTART_KEY, restart.toString())
            }
            if (pendingTemplate != null) {
                put(DevWorkspace.PENDING_TEMPLATE_KEY, pendingTemplate)
            }
        }

    /** [Watch] over a fixed list of events; a real [Watch] blocks on the API server instead. */
    private class FakeWatch(events: List<Watch.Response<Any>>) : Watch<Any>(null, null, null, null) {

        private val delegate: MutableIterator<Watch.Response<Any>> =
            events.toMutableList().iterator()

        override fun iterator(): MutableIterator<Watch.Response<Any>> = delegate

        override fun hasNext(): Boolean = delegate.hasNext()

        override fun next(): Watch.Response<Any> = delegate.next()

        override fun close() {
            // nothing to release
        }
    }

    private companion object {
        private const val TIMEOUT = 5_000L

        /** Bounds a stop that is reported at the baseline: the watch must not take the retry delay. */
        private const val STOP_TIMEOUT = 900L
        private val SETTLE = 200.milliseconds
    }
}
