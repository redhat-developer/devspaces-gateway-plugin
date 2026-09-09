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
package com.redhat.devtools.gateway.devworkspace

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JetBrainsWorkspaceWatchParamsTest {

    @Test
    fun `only Jetbrains workspace out of 1 JetBrains and 1 VS Code is watched`() {
        val jetBrains = workspace(uid = "uid-jb", cheEditor = "eclipse/che-idea-server/latest")
        val vscode = workspace(uid = "uid-vscode", cheEditor = "eclipse/che-code/latest")
        val items = listOf(
            DevWorkspaceListItem(jetBrains, WorkspaceEditorInfoProvider.create(jetBrains, emptyMap())),
            DevWorkspaceListItem(vscode, WorkspaceEditorInfoProvider.create(vscode, emptyMap()))
        )

        val params =
            WorkspaceEditorInfoProvider.getJetBrainsWorkspaceWatchParams("ns", items, emptyMap(), "42")

        assertThat(params).isEqualTo(JetBrainsWorkspaceWatchParams("ns", "42", 1))
        assertThat(params.shouldWatch).isTrue()
    }

    @Test
    fun `VS Code only namespace is not watched`() {
        val vscode = workspace(uid = "uid-vscode", cheEditor = "eclipse/che-code/latest")
        val items = listOf(
            DevWorkspaceListItem(vscode, WorkspaceEditorInfoProvider.create(vscode, emptyMap()))
        )

        val params =
            WorkspaceEditorInfoProvider.getJetBrainsWorkspaceWatchParams("ns", items, emptyMap(), "42")

        assertThat(params.shouldWatch).isFalse()
        assertThat(params.jetbrainsWorkspaceCount).isEqualTo(0)
    }

    @Test
    fun `JetBrains via template only is watched`() {
        val dw = workspace(uid = "uid-template", cheEditor = null)
        val templates = mapOf(
            "uid-template" to listOf(
                DevWorkspaceTemplate(
                    metadata = DevWorkspaceTemplateMetadata(
                        name = "template",
                        namespace = "ns",
                        pluginRegistryUrl = null,
                        ownerRefencesUids = listOf("uid-template")
                    ),
                    spec = DevWorkspaceTemplateSpec(
                        components = listOf(mapOf("volume" to mapOf("name" to "idea-server")))
                    )
                )
            )
        )
        val items = listOf(
            DevWorkspaceListItem(dw, WorkspaceEditorInfoProvider.create(dw, templates))
        )

        val params =
            WorkspaceEditorInfoProvider.getJetBrainsWorkspaceWatchParams("ns", items, templates, "7")

        assertThat(params).isEqualTo(JetBrainsWorkspaceWatchParams("ns", "7", 1))
        assertThat(params.shouldWatch).isTrue()
    }

    @Test
    fun `params report JetBrains workspace count`() {
        val jetBrains1 = workspace(uid = "uid-jb1", cheEditor = "eclipse/che-idea-server/latest")
        val jetBrains2 = workspace(uid = "uid-jb2", cheEditor = "eclipse/che-pycharm/latest")
        val vscode = workspace(uid = "uid-vscode", cheEditor = "eclipse/che-code/latest")
        val items = listOf(
            DevWorkspaceListItem(jetBrains1, WorkspaceEditorInfoProvider.create(jetBrains1, emptyMap())),
            DevWorkspaceListItem(jetBrains2, WorkspaceEditorInfoProvider.create(jetBrains2, emptyMap())),
            DevWorkspaceListItem(vscode, WorkspaceEditorInfoProvider.create(vscode, emptyMap()))
        )

        val params =
            WorkspaceEditorInfoProvider.getJetBrainsWorkspaceWatchParams("ns", items, emptyMap(), "42")

        assertThat(params.namespace).isEqualTo("ns")
        assertThat(params.resourceVersion).isEqualTo("42")
        assertThat(params.jetbrainsWorkspaceCount).isEqualTo(2)
    }

    private fun workspace(uid: String, cheEditor: String?): DevWorkspace {
        val annotations = if (cheEditor != null) mapOf("che.eclipse.org/che-editor" to cheEditor) else emptyMap()
        return DevWorkspace(
            DevWorkspaceObjectMeta(name = "ws-$uid", namespace = "ns", uid = uid, annotations, emptyMap()),
            DevWorkspaceSpec(started = true),
            DevWorkspaceStatus(phase = "Running")
        )
    }
}
