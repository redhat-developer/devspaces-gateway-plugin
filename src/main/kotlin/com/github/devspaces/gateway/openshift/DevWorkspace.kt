/*
 * Copyright (c) 2024 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.github.devspaces.gateway.openshift

import java.util.Collections.emptyMap

data class DevWorkspace(
    val metadata: DevWorkspaceObjectMeta,
    val spec: DevWorkspaceSpec,
    val status: DevWorkspaceStatus
) {
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

        return true
    }
}

data class DevWorkspaceObjectMeta(
    val name: String,
    val namespace: String
) {
    companion object {
        fun from(map: Any) = object {
            val name = Utils.getValue(map, arrayOf("name"))
            val namespace = Utils.getValue(map, arrayOf("namespace"))

            val data = DevWorkspaceObjectMeta(
                name as String,
                namespace as String
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
}

