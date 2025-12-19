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
package com.redhat.devtools.gateway.util

import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds

class ProgressCountdown(private val delegate: ProgressIndicator) : ProgressIndicator by delegate, Disposable {
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var baseText2: String? = null

    companion object {
        const val EN_DASH = "\u2013"
    }

    fun update(
        title: String? = null,
        message: String? = null,
        countdownSeconds: Long? = null
    ) {
        title?.let { delegate.text = it }
        message?.let {
            baseText2 = it
            delegate.text2 = it
        }

        if (countdownSeconds != null && countdownSeconds > 0) startCountdown(countdownSeconds)
        else stopCountdown()
    }

    private fun startCountdown(seconds: Long) {
        job?.cancel()
        var remaining = seconds
        job = scope.launch {
            while (remaining > 0 && isActive && !delegate.isCanceled) {
                delegate.text2 = buildText2WithSuffix(remaining)
                delay(1.seconds)
                remaining--
            }
            delegate.text2 = baseText2
        }
    }

    fun stopCountdown() {
        job?.cancel()
        job = null
        delegate.text2 = baseText2
    }

    override fun dispose() {
        stopCountdown()
        scope.cancel()
    }

    private fun buildText2WithSuffix(secondsLeft: Long): String =
        buildString {
            baseText2?.let { append(it) }
            append(" \t$EN_DASH $secondsLeft second${if (secondsLeft != 1L) "s" else ""} left")
        }

    data class ProgressEvent (
        val title: String? = null,
        val message: String? = null,
        val countdownSeconds: Long? = null
    )
}
