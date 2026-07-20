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

import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceObjectMeta
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceSpec
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DevSpacesContextTest {

    @Test
    fun `removeWorkspace clears entry when instance differs but name and namespace match`() {
        val context = DevSpacesContext()
        val connected = createDevWorkspace(
            name = "ws",
            namespace = "ns",
            phase = "Running",
            annotations = mapOf("a" to "1")
        )
        val refreshed = createDevWorkspace(
            name = "ws",
            namespace = "ns",
            phase = "Stopped",
            annotations = mapOf("a" to "2")
        )

        context.addWorkspace(connected)
        assertThat(context.isWorkspaceActive(refreshed)).isTrue()

        context.removeWorkspace(refreshed)

        assertThat(context.activeWorkspaces).isEmpty()
        assertThat(context.isWorkspaceActive(connected)).isFalse()
    }

    @Test
    fun `addWorkspace dedupes by name and namespace`() {
        val context = DevSpacesContext()
        val first = createDevWorkspace(name = "ws", namespace = "ns", phase = "Running")
        val second = createDevWorkspace(name = "ws", namespace = "ns", phase = "Starting")

        context.addWorkspace(first)
        context.addWorkspace(second)

        assertThat(context.activeWorkspaces).hasSize(1)
    }

    private fun createDevWorkspace(
        name: String,
        namespace: String,
        phase: String = "Running",
        annotations: Map<String, String> = emptyMap()
    ): DevWorkspace {
        return DevWorkspace(
            DevWorkspaceObjectMeta(
                name = name,
                namespace = namespace,
                uid = "uid-$name",
                annotations = annotations,
                labels = emptyMap()
            ),
            DevWorkspaceSpec(started = phase != "Stopped"),
            DevWorkspaceStatus(phase = phase)
        )
    }
}
