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

/**
 * Produces a sequence of delays that start at [initialMillis] and double per call,
 * capped at [maxMillis].
 */
class ExponentialBackoff(
    private val initialMillis: Long = 500,
    private val maxMillis: Long = 5000,
) {
    private var current = initialMillis.coerceIn(0L, maxMillis)

    fun nextDelayMillis(): Long {
        val delay = current
        current = if (current >= maxMillis / 2) maxMillis else current * 2
        return delay
    }

    fun reset() {
        current = initialMillis.coerceIn(0L, maxMillis)
    }
}
