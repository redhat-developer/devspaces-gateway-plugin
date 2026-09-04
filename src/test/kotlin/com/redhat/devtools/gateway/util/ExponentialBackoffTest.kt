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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExponentialBackoffTest {

    @Test
    fun `delays double per call and cap at max`() {
        val backoff = ExponentialBackoff()
        val delays = List(8) { backoff.nextDelayMillis() }
        assertThat(delays).containsExactly(500L, 1000L, 2000L, 4000L, 5000L, 5000L, 5000L, 5000L)
    }

    @Test
    fun `reset restarts at initial delay`() {
        val backoff = ExponentialBackoff()
        repeat(3) { backoff.nextDelayMillis() }
        backoff.reset()
        assertThat(backoff.nextDelayMillis()).isEqualTo(500L)
    }

    @Test
    fun `custom initial and max values are honored`() {
        val backoff = ExponentialBackoff(initialMillis = 100L, maxMillis = 250L)
        val delays = List(4) { backoff.nextDelayMillis() }
        assertThat(delays).containsExactly(100L, 200L, 250L, 250L)
    }

    @Test
    fun `sequence stays bounded even after many calls`() {
        val backoff = ExponentialBackoff()
        repeat(200) {
            assertThat(backoff.nextDelayMillis()).isBetween(500L, 5000L)
        }
    }
}
