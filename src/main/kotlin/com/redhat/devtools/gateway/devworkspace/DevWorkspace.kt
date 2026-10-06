/*
 * Copyright (c) 2024-2025 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.devworkspace

import com.redhat.devtools.gateway.openshift.Utils
import java.util.Collections.emptyMap

data class DevWorkspace(
    private val metadata: DevWorkspaceObjectMeta,
    private val spec: DevWorkspaceSpec,
    private val status: DevWorkspaceStatus
) {
    val namespace: String
        get() {
            return metadata.namespace
        }

    val name: String
        get() {
            return metadata.name
        }

    val uid: String
        get() {
            return metadata.uid
        }

    /**
     * The object's `metadata.resourceVersion`, or `null` if unknown. Used to detect and
     * discard out-of-order/stale redeliveries of this workspace (e.g. a relist that read
     * from an apiserver replica whose watch cache lags the latest write) — never used for
     * row identity (see [equals]).
     */
    val resourceVersion: String?
        get() {
            return metadata.resourceVersion
        }

    val started: Boolean
        get() {
            return spec.started
        }

    val phase: String
        get() {
            return status.phase
        }

    val running: Boolean
        get() {
            return status.running
        }

    val annotations: Map<String, String>
        get() {
            return metadata.annotations
        }

    val labels: Map<String, String>
        get() {
            return metadata.labels
        }

    companion object {
        fun from(map: Any?) = object {
            val metadata = Utils.getValue(map, arrayOf("metadata")) ?: emptyMap<String, Any>()
            val spec = Utils.getValue(map, arrayOf("spec")) ?: emptyMap<String, Any>()
            val status = Utils.getValue(map, arrayOf("status")) ?: emptyMap<String, Any>()

            val data = DevWorkspace(
                DevWorkspaceObjectMeta.from(metadata),
                DevWorkspaceSpec.from(spec),
                DevWorkspaceStatus.from(status)
            )
        }.data
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DevWorkspace

        return metadata.name == other.metadata.name &&
                metadata.namespace == other.metadata.namespace
    }

    override fun hashCode(): Int {
        var result = metadata.name.hashCode()
        result = 31 * result + metadata.namespace.hashCode()
        return result
    }
}

data class DevWorkspaceObjectMeta(
    val name: String,
    val namespace: String,
    val uid: String,
    val annotations: Map<String, String>,
    val labels: Map<String, String>,
    val resourceVersion: String? = null
) {
    companion object {
        fun from(map: Any) = object {
            val name = Utils.getValue(map, arrayOf("name"))
            val namespace = Utils.getValue(map, arrayOf("namespace"))
            val uid = Utils.getValue(map, arrayOf("uid"))
            @Suppress("UNCHECKED_CAST")
            val annotations = (Utils.getValue(map, arrayOf("annotations")) as? Map<String, String>)
                ?: emptyMap<String, String>()
            @Suppress("UNCHECKED_CAST")
            val labels = (Utils.getValue(map, arrayOf("labels")) as? Map<String, String>)
                ?: emptyMap<String, String>()
            val resourceVersion = Utils.getValue(map, arrayOf("resourceVersion")) as? String

            val data = DevWorkspaceObjectMeta(
                name as String,
                namespace as String,
                uid as String,
                annotations,
                labels,
                resourceVersion
            )
        }.data
    }
}

data class DevWorkspaceSpec(
    val started: Boolean
) {
    companion object {
        fun from(map: Any) = object {
            val started = Utils.getValue(map, arrayOf("started")) ?: false

            val data = DevWorkspaceSpec(
                started as Boolean
            )
        }.data
    }
}

data class DevWorkspaceStatus(
    val phase: String
) {
    companion object {
        fun from(map: Any) = object {
            val phase = Utils.getValue(map, arrayOf("phase")) ?: ""

            val data = DevWorkspaceStatus(
                phase as String
            )
        }.data
    }

    val running: Boolean
        get() {
            return phase == "Running"
        }
}

