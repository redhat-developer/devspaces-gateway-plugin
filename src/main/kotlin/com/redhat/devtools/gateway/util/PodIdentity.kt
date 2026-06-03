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

import io.kubernetes.client.openapi.models.V1Pod

/**
 * Returns a human-readable description of a pod for log messages.
 *
 * Format: `<name> (uid=<uid>[, terminating])`
 *
 * @param pod the pod to describe
 * @return a formatted string like `"my-pod (uid=abc-123)"` or `"my-pod (uid=abc-123, terminating)"`
 *   if the pod has a deletion timestamp. Falls back to `"unknown"` for name or uid if missing.
 */
fun podLogIdentity(pod: V1Pod): String {
    val namespace = pod.metadata?.namespace
    val name = pod.metadata?.name ?: "unknown"
    val label = if (namespace != null) "$namespace/$name" else name
    val uid = pod.metadata?.uid ?: "unknown"
    val terminating = if (pod.metadata?.deletionTimestamp != null) ", terminating" else ""
    return "$label (uid=$uid$terminating)"
}

/**
 * Returns a stable identity key for a pod, used to detect pod rolls.
 *
 * Prefers the pod's UID as the identity, falling back to the pod's name if
 * the UID is null. Returns null only if both UID and name are null.
 *
 * @param pod the pod to derive an identity from
 * @return the pod's UID, its name if UID is null, or null if both are absent
 */
fun podIdentity(pod: V1Pod): String? {
    val uid = pod.metadata?.uid
    if (uid != null) return uid
    val namespace = pod.metadata?.namespace
    val name = pod.metadata?.name
    return if (name != null) {
        if (namespace != null) "$namespace/$name" else name
    } else null
}
