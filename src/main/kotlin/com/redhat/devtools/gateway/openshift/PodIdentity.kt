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

/**
 * Whether two pod references name the same pod.
 *
 * The UID is a pod's identity (even during a roll). The namespace/name
 * pair is the fallback for a pod without a UID (a synthetic pod in a test).
 */
internal fun isSamePod(first: V1Pod, second: V1Pod): Boolean {
    val firstUid = first.metadata?.uid
    val secondUid = second.metadata?.uid
    if (firstUid != null && secondUid != null) {
        return firstUid == secondUid
    }
    return first.metadata?.namespace == second.metadata?.namespace
            && first.metadata?.name == second.metadata?.name
}

/** Identifies a pod in log lines: the name alone cannot tell two generations of a pod apart. */
internal fun describePod(pod: V1Pod): String =
    "name=${pod.metadata?.name}, uid=${pod.metadata?.uid}"
