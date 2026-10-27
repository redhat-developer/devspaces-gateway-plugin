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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProgressCancellationTest {

    @Test
    fun `#checkProgressCanceled throws when indicator is stopped`() {
        val indicator = mockk<ProgressIndicator>(relaxed = true)
        every { indicator.isCanceled } returns false
        every { indicator.isRunning } returns false

        var caught: ProcessCanceledException? = null
        try {
            checkProgressCanceled(indicator)
        } catch (e: ProcessCanceledException) {
            caught = e
        }

        assertThat(caught).isNotNull
    }

    @Test
    fun `#updateProgress throws when indicator is canceled`() {
        val indicator = mockk<ProgressIndicator>(relaxed = true)
        every { indicator.isRunning } returns true
        every { indicator.isCanceled } returns true

        var caught: ProcessCanceledException? = null
        try {
            indicator.updateProgress("Waiting for IDE to be ready...", 0.5)
        } catch (e: ProcessCanceledException) {
            caught = e
        }

        assertThat(caught).isNotNull
        verify(exactly = 0) { indicator.fraction = any() }
    }

    @Test
    fun `#updateProgress treats disposed indicator write failure as cancellation`() {
        val indicator = mockk<ProgressIndicator>(relaxed = true)
        every { indicator.isRunning } returns true
        every { indicator.isCanceled } returns false
        every { indicator.fraction = any() } throws IllegalArgumentException("indicator is disposed")

        var caught: ProcessCanceledException? = null
        try {
            indicator.updateProgress("Waiting for IDE to be ready...", 0.5)
        } catch (e: ProcessCanceledException) {
            caught = e
        }

        assertThat(caught).isNotNull
    }
}
