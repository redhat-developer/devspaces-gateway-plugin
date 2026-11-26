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
package com.redhat.devtools.gateway.testutil

import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodCondition
import io.kubernetes.client.openapi.models.V1PodStatus
import java.time.OffsetDateTime

private const val POD_PHASE_RUNNING = "Running"
private const val POD_READY_CONDITION = "Ready"
private const val CONDITION_STATUS_TRUE = "True"

/**
 * A pod as the gateway sees it, built for tests: running and ready by default, since most tests
 * need a pod that serves. A test that models a pod roll, a readiness failure, or a terminal phase
 * overrides [uid], [creationTimestamp], [deletionTimestamp], or the status after construction.
 *
 * [uid] identifies a pod generation:
 * [com.redhat.devtools.gateway.openshift.isSamePod] compares it before falling back to
 * namespace/name, so each generation of a rolled pod needs its own UID.
 */
internal fun podWith(
    name: String,
    uid: String? = null,
    namespace: String? = null,
    creationTimestamp: OffsetDateTime? = null,
    deletionTimestamp: OffsetDateTime? = null,
): V1Pod = V1Pod().apply {
    metadata = V1ObjectMeta().apply {
        this.name = name
        this.uid = uid
        this.namespace = namespace
        this.creationTimestamp = creationTimestamp
        this.deletionTimestamp = deletionTimestamp
    }
    status = V1PodStatus().apply {
        phase = POD_PHASE_RUNNING
        conditions = listOf(
            V1PodCondition().apply {
                type = POD_READY_CONDITION
                status = CONDITION_STATUS_TRUE
            }
        )
    }
}
