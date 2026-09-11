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
package com.redhat.devtools.gateway.view.steps.workspaces

import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceObjectMeta
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceSpec
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceStatus
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceTemplate
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceTemplateMetadata
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceTemplateSpec
import com.redhat.devtools.gateway.devworkspace.WorkspaceEditorInfoProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkspacesWatchFilterTest {

    @Test
    fun `createFilter rejects VS Code workspace and accepts JetBrains workspace`() {
        val jetBrainsDw = DevWorkspace(
            DevWorkspaceObjectMeta(name = "jb", namespace = "ns", uid = "uid-jb", emptyMap(), emptyMap()),
            DevWorkspaceSpec(started = true),
            DevWorkspaceStatus(phase = "Running")
        )
        val vscodeDw = DevWorkspace(
            DevWorkspaceObjectMeta(
                name = "vscode",
                namespace = "ns",
                uid = "uid-vscode",
                annotations = mapOf("che.eclipse.org/che-editor" to "eclipse/che-code/latest"),
                labels = emptyMap()
            ),
            DevWorkspaceSpec(started = true),
            DevWorkspaceStatus(phase = "Running")
        )
        val templateMapsByNamespace: Map<String, Map<String, List<DevWorkspaceTemplate>>> = mapOf(
            "ns" to mapOf(
                "uid-jb" to listOf(
                    DevWorkspaceTemplate(
                        metadata = DevWorkspaceTemplateMetadata(
                            name = "jb-template",
                            namespace = "ns",
                            pluginRegistryUrl = null,
                            ownerRefencesUids = listOf("uid-jb")
                        ),
                        spec = DevWorkspaceTemplateSpec(
                            components = listOf(mapOf("volume" to mapOf("name" to "idea-server")))
                        )
                    )
                )
            )
        )

        // Same createFilter lambda pattern as WorkspacesWatch: namespace -> templateMap lookup
        // then WorkspaceEditorInfoProvider.isJetBrainsWorkspace.
        val createFilter: (String) -> ((DevWorkspace) -> Boolean) = { namespace ->
            { dw ->
                val templateMap = templateMapsByNamespace[namespace] ?: emptyMap()
                WorkspaceEditorInfoProvider.isJetBrainsWorkspace(dw, templateMap)
            }
        }

        val filter = createFilter("ns")
        assertThat(filter(vscodeDw)).isFalse()
        assertThat(filter(jetBrainsDw)).isTrue()
    }
}
