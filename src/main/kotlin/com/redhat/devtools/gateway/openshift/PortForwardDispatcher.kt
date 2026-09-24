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
package com.redhat.devtools.gateway.openshift

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated dispatcher for long-lived port-forward accept/copy loops.
 *
 * Must not use `Dispatchers.IO`: port-forward sessions block indefinitely on
 * accept/copy, and running them on the shared IO pool would starve other IO
 * work (CRW-12992). Backed by a cached thread pool of daemon threads named
 * `devspaces-port-forward-${n}` so long-lived forward sessions can be
 * identified in thread dumps.
 */
internal object PortForwardDispatcher {

    private val threadCounter = AtomicInteger(0)

    val dispatcher: CoroutineDispatcher = Executors
        .newCachedThreadPool { runnable ->
            Thread(runnable, "devspaces-port-forward-${threadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
        .asCoroutineDispatcher()
}
