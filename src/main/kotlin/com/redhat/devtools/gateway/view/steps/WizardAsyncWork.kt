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
package com.redhat.devtools.gateway.view.steps

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background work for a wizard step that may show modal UI (e.g. TLS trust dialogs)
 * before the step advances.
 *
 * @param onFinished call when done; `true` means advance to the next step. The companion
 * [execute] delivers this callback on the EDT.
 */
fun interface WizardAsyncWork {
    fun run(indicator: ProgressIndicator, onFinished: (Boolean) -> Unit)

    companion object {
        private val generation = AtomicInteger(0)

        /**
         * Runs [work] on a cancellable background task. Stale callbacks are ignored after
         * [invalidatePending] or when a newer [execute] call starts.
         */
        fun execute(
            title: String,
            work: WizardAsyncWork,
            onFinished: (Boolean) -> Unit,
        ) {
            val gen = generation.incrementAndGet()
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(null, title, true) {
                    override fun run(indicator: ProgressIndicator) {
                        work.run(indicator) { advance ->
                            finishOnEdt(gen, advance, onFinished)
                        }
                    }

                    override fun onCancel() {
                        finishOnEdt(gen, false, onFinished)
                    }

                    override fun onThrowable(error: Throwable) {
                        finishOnEdt(gen, false, onFinished)
                    }
                },
            )
        }

        fun invalidatePending() {
            generation.incrementAndGet()
        }

        private fun finishOnEdt(gen: Int, advance: Boolean, onFinished: (Boolean) -> Unit) {
            ApplicationManager.getApplication().invokeLater {
                if (gen == generation.get()) {
                    onFinished(advance)
                }
            }
        }
    }
}
