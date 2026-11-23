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

import io.kubernetes.client.openapi.models.V1Pod

private const val POD_PHASE_RUNNING = "Running"
private const val POD_PHASE_FAILED = "Failed"
private const val POD_PHASE_SUCCEEDED = "Succeeded"
private const val POD_READY_CONDITION = "Ready"
private const val CONDITION_STATUS_TRUE = "True"

/*
 * The Kubernetes Java client models pod phase and condition status as plain strings, so these wire
 * values are owned here rather than reused from the SDK. They stay private: callers ask about
 * behaviour ("is this pod serving?"), not about the strings that encode it.
 */

/**
 * Whether the pod is being deleted.
 *
 * A terminating pod may still report phase `Running`, so this must be checked before the phase:
 * only a pod without a deletion timestamp can serve.
 */
internal fun V1Pod.isTerminating(): Boolean = metadata?.deletionTimestamp != null

/** Whether the pod's phase is `Running`, regardless of readiness or termination. */
internal fun V1Pod.isRunning(): Boolean = status?.phase == POD_PHASE_RUNNING

/** Whether the pod passed its readiness probe, i.e. carries a `Ready` condition that is `True`. */
internal fun V1Pod.isReady(): Boolean =
    status?.conditions.orEmpty().any { condition ->
        condition.type == POD_READY_CONDITION && condition.status == CONDITION_STATUS_TRUE
    }

/**
 * Whether the pod can serve right now: running, ready, and not terminating.
 *
 * During a pod roll the old pod is terminating and the new one is starting, so neither alone is
 * enough to answer this.
 */
internal fun V1Pod.isRunningAndReady(): Boolean = !isTerminating() && isRunning() && isReady()

/** Whether the pod reached a terminal phase (`Failed` or `Succeeded`) and will never serve again. */
internal fun V1Pod.isInTerminalPhase(): Boolean =
    status?.phase == POD_PHASE_FAILED || status?.phase == POD_PHASE_SUCCEEDED
