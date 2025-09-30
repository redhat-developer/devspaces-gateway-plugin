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
package com.redhat.devtools.gateway.openshift

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

    val editor: String
        get() {
            return metadata.editor
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

        if (metadata.name != other.metadata.name) return false
        if (metadata.namespace != other.metadata.namespace) return false
        if (metadata.editor != other.metadata.editor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = metadata.hashCode()
        result = 31 * result + spec.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }
}

data class DevWorkspaceObjectMeta(
    val name: String,
    val namespace: String,
    val editor: String
) {
    companion object {
        fun from(map: Any) = object {
            val name = Utils.getValue(map, arrayOf("name"))
            val namespace = Utils.getValue(map, arrayOf("namespace"))
            val editor = Utils.getValue(map, arrayOf("annotations", "che.eclipse.org/che-editor")) ?: "unknown"

            val data = DevWorkspaceObjectMeta(
                name as String,
                namespace as String,
                editor as String
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

