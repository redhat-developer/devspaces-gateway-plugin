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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ThrottledRestartCheckTest {

    @Test
    fun `#isRestarting queries at most once within the ttl`() {
        val queries = AtomicInteger()
        val check = ThrottledRestartCheck(
            queryRestartInProgress = { queries.incrementAndGet(); false },
            ttl = 60.seconds,
        )

        check.isRestarting()
        check.isRestarting()
        check.isRestarting()

        assertThat(queries.get()).isEqualTo(1)
    }

    @Test
    fun `#isRestarting re-queries after the ttl elapsed`() {
        val queries = AtomicInteger()
        val check = ThrottledRestartCheck(
            queryRestartInProgress = { queries.incrementAndGet(); true },
            ttl = 10.milliseconds,
        )

        check.isRestarting()

        runBlocking {
            delay(50.milliseconds)
        }

        check.isRestarting()

        assertThat(queries.get()).isEqualTo(2)
    }

    @Test
    fun `#isRestarting returns the queried value`() {
        assertThat(ThrottledRestartCheck({ true }, ttl = 60.seconds).isRestarting()).isTrue()
        assertThat(ThrottledRestartCheck({ false }, ttl = 60.seconds).isRestarting()).isFalse()
    }
}
