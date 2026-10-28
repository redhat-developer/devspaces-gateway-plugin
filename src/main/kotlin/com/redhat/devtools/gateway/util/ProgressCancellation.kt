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
package com.redhat.devtools.gateway.util

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val INDICATOR_POLL_DELAY = 500.milliseconds

/**
 * Polls [indicator] and runs [cancelAction] when the user cancels the operation.
 */
fun CoroutineScope.launchProgressCancelWatcher(
    indicator: ProgressIndicator,
    cancelAction: suspend () -> Unit,
): Job = launch(Dispatchers.Default) {
    while (isActive) {
        if (indicator.isCanceled) {
            cancelAction()
            return@launch
        }
        delay(INDICATOR_POLL_DELAY)
    }
}

/**
 * Runs [block] while polling [indicator] for cancellation.
 * The watcher is stopped when [block] completes so the caller's [coroutineScope] does not hang.
 */
suspend fun <T> withProgressCancelWatcher(
    indicator: ProgressIndicator,
    cancelAction: suspend () -> Unit,
    block: suspend CoroutineScope.() -> T,
): T = coroutineScope {
    val cancelJob = launchProgressCancelWatcher(indicator, cancelAction)
    try {
        block()
    } finally {
        cancelJob.cancelAndJoin()
    }
}

fun checkProgressCanceled(indicator: ProgressIndicator) {
    if (indicator.isCanceled || !indicator.isRunning) {
        throw ProcessCanceledException()
    }
}

/**
 * Updates progress text and fraction.
 * Throws [ProcessCanceledException] when the indicator is canceled, stopped, or disposed.
 */
fun ProgressIndicator.updateProgress(text: String, fraction: Double) {
    checkProgressCanceled(this)
    writeToIndicator {
        this.fraction = fraction
        this.text = text
    }
}

/**
 * Updates secondary progress text.
 * Throws [ProcessCanceledException] when the indicator is canceled, stopped, or disposed.
 */
fun ProgressIndicator.setProgressText2(text: String?) {
    checkProgressCanceled(this)
    writeToIndicator {
        this.text2 = text
    }
}

private inline fun ProgressIndicator.writeToIndicator(block: ProgressIndicator.() -> Unit) {
    try {
        block()
    } catch (e: IllegalArgumentException) {
        throw ProcessCanceledException(e)
    } catch (e: IllegalStateException) {
        throw ProcessCanceledException(e)
    }
}

/** Clears [ProgressIndicator.text2]; ignores errors when the indicator is already disposed. */
fun ProgressIndicator?.clearProgressText2Safely() {
    runCatching { this?.text2 = null }
}

/**
 * Sleeps for [duration], polling [indicator] for cancellation or disposal.
 */
suspend fun delayRespectingProgress(indicator: ProgressIndicator?, duration: Duration) {
    if (indicator == null) {
        delay(duration)
        return
    }
    var remaining = duration
    while (remaining > Duration.ZERO) {
        checkProgressCanceled(indicator)
        val step = minOf(INDICATOR_POLL_DELAY, remaining)
        delay(step)
        remaining -= step
    }
}

/**
 * Runs work on IO and honors progress cancellation before and after [block].
 */
suspend fun <T> withProgressCancellation(
    indicator: ProgressIndicator,
    block: suspend () -> T,
): T {
    checkProgressCanceled(indicator)
    return withContext(Dispatchers.IO) {
        block()
    }.also { checkProgressCanceled(indicator) }
}
