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
package com.redhat.devtools.gateway

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.redhat.devtools.gateway.util.WorkspaceSessionProgress
import com.redhat.devtools.gateway.util.checkProgressCanceled
import com.redhat.devtools.gateway.util.clearProgressText2Safely
import com.redhat.devtools.gateway.util.delayRespectingProgress
import com.redhat.devtools.gateway.util.updateProgress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shows delayed progress when port-forward cannot resolve a workspace pod for an extended period.
 *
 * Progress appears only after [showAfter] of sustained unavailability so brief glitches stay silent.
 * Suppressed while pod-roll reconnect or annotated restart handlers own recovery.
 */
internal class ForwardRecoveryProgress(
    private val scope: CoroutineScope,
    private val sessionCtx: ThinClientSessionContext,
    private val isWorkspaceRestartInProgress: () -> Boolean,
    private val onCanceled: () -> Unit,
    private val showAfter: Duration = DEFAULT_SHOW_AFTER,
) {
    private val waitingSinceMillis = AtomicLong(0)
    private val progressActive = AtomicBoolean(false)
    private var showProgressJob: Job? = null

    companion object {
        private val DEFAULT_SHOW_AFTER: Duration = 20_000.milliseconds
        private val PROGRESS_POLL_DELAY: Duration = 500.milliseconds
        const val PROGRESS_TITLE: String = "Reconnecting to workspace"
    }

    /** Called when no workspace pod is available yet. */
    fun onPodUnavailable() {
        if (shouldSuppress()) {
            reset()
            return
        }
        if (waitingSinceMillis.compareAndSet(0, System.currentTimeMillis())) {
            scheduleShowProgress()
        }
    }

    /** Called when a pod is resolved for port-forwarding. */
    fun onPodResolved() {
        reset()
    }

    /** Called when a dedicated recovery handler (pod-roll reconnect) takes over. */
    fun dismiss() {
        reset()
    }

    private fun shouldSuppress(): Boolean =
        isWorkspaceRestartInProgress() || sessionCtx.reconnecting.get()

    private fun scheduleShowProgress() {
        showProgressJob?.cancel()
        showProgressJob = scope.launch {
            delay(showAfter)
            if (waitingSinceMillis.get() != 0L && !shouldSuppress()) {
                maybeShowProgress()
            }
        }
    }

    private fun maybeShowProgress() {
        if (!progressActive.compareAndSet(false, true)) return
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, PROGRESS_TITLE, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                runBlocking {
                    try {
                        indicator.updateProgress(WorkspaceSessionProgress.WAITING_FOR_POD, 0.0)
                        while (waitingSinceMillis.get() != 0L && !shouldSuppress()) {
                            delayRespectingProgress(indicator, PROGRESS_POLL_DELAY)
                        }
                    } catch (e: ProcessCanceledException) {
                        onCanceled()
                        throw e
                    } finally {
                        indicator.clearProgressText2Safely()
                        progressActive.set(false)
                    }
                }
            }
        })
    }

    private fun reset() {
        showProgressJob?.cancel()
        showProgressJob = null
        waitingSinceMillis.set(0)
    }
}
