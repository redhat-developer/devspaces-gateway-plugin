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

import io.kubernetes.client.openapi.models.V1Pod

/**
 * Outcome of resolving the workspace pod for session recovery routing.
 *
 * Domain vocabulary only — no port-forward or progress semantics.
 */
sealed class PodResolution {
    data class Ready(val pod: V1Pod) : PodResolution()
    data object Unavailable : PodResolution()
    data class RollDelegated(val pod: V1Pod) : PodResolution()
    data object RestartSuppressed : PodResolution()
}
