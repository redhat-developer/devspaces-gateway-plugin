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

class WorkspaceEditorInfoProviderTest {

    @Test
    fun `#isJetBrainsEditor returns true for template with idea-server volume`() {
        val dw = DevWorkspace(
            DevWorkspaceObjectMeta(name = "test", namespace = "ns", uid = "uid1", emptyMap(), emptyMap()),
            DevWorkspaceSpec(started = true),
            DevWorkspaceStatus(phase = "Running")
        )
        val templateMap = mapOf(
            "uid1" to listOf(
                DevWorkspaceTemplate(
                    metadata = DevWorkspaceTemplateMetadata(name = "test", namespace = "ns", pluginRegistryUrl = null, ownerRefencesUids = listOf("uid1")),
                    spec = DevWorkspaceTemplateSpec(components = listOf(mapOf("volume" to mapOf("name" to "idea-server"))))
                )
            )
        )
        assertThat(WorkspaceEditorInfoProvider.isJetBrainsEditor(dw, templateMap)).isTrue()
    }

    @Test
    fun `#isJetBrainsEditor returns false for template without idea-server volume`() {
        val dw = DevWorkspace(
            DevWorkspaceObjectMeta(name = "test", namespace = "ns", uid = "uid1", emptyMap(), emptyMap()),
            DevWorkspaceSpec(started = true),
            DevWorkspaceStatus(phase = "Running")
        )
        val templateMap = mapOf(
            "uid1" to listOf(
                DevWorkspaceTemplate(
                    metadata = DevWorkspaceTemplateMetadata(name = "test", namespace = "ns", pluginRegistryUrl = null, ownerRefencesUids = listOf("uid1")),
                    spec = DevWorkspaceTemplateSpec(components = listOf(mapOf("volume" to mapOf("name" to "vscode-server"))))
                )
            )
        )
        assertThat(WorkspaceEditorInfoProvider.isJetBrainsEditor(dw, templateMap)).isFalse()
    }

    @Test
    fun `#create resolves template-based JetBrains editor to JETBRAINS`() {
        val dw = DevWorkspace(
            DevWorkspaceObjectMeta(name = "test", namespace = "ns", uid = "uid1", emptyMap(), emptyMap()),
            DevWorkspaceSpec(started = true),
            DevWorkspaceStatus(phase = "Running")
        )
        val templateMap = mapOf(
            "uid1" to listOf(
                DevWorkspaceTemplate(
                    metadata = DevWorkspaceTemplateMetadata(name = "test", namespace = "ns", pluginRegistryUrl = null, ownerRefencesUids = listOf("uid1")),
                    spec = DevWorkspaceTemplateSpec(components = listOf(mapOf("volume" to mapOf("name" to "idea-server"))))
                )
            )
        )
        val info = WorkspaceEditorInfoProvider.create(dw, templateMap)
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.JETBRAINS)
        assertThat(info.tooltip).isEqualTo("JetBrains")
    }

    @Test
    fun `#create maps webstorm editor to WEBSTORM`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-webstorm-server/latest")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.WEBSTORM)
        assertThat(info.tooltip).isEqualTo("JetBrains WebStorm (desktop)")
    }

    @Test
    fun `#create maps pycharm editor to PYCHARM`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-pycharm-server/latest")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.PYCHARM)
        assertThat(info.tooltip).isEqualTo("PyCharm")
    }

    @Test
    fun `#create maps unknown che-server editor to JETBRAINS`() {
        val dw = createDevWorkspaceWithEditor("che-foo-server")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.JETBRAINS)
        assertThat(info.tooltip).isEqualTo("JetBrains")
    }

    @Test
    fun `#create maps vscode editor to VSCODE`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-code/latest")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.VSCODE)
        assertThat(info.tooltip).isEqualTo("VS Code - Open Source")
    }

    @Test
    fun `#create maps intellij editor to INTELLIJ_IDEA`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-idea-server/latest")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.INTELLIJ_IDEA)
        assertThat(info.tooltip).isEqualTo("IntelliJ IDEA Ultimate (desktop)")
    }

    @Test
    fun `#create maps clion editor to CLION`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-clion-server/latest")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.CLION)
        assertThat(info.tooltip).isEqualTo("JetBrains CLion (desktop)")
    }

    @Test
    fun `#create maps goland editor to GOLAND`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-goland-server/latest")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.GOLAND)
        assertThat(info.tooltip).isEqualTo("JetBrains GoLand (desktop)")
    }

    @Test
    fun `#create maps phpstorm editor to PHPSTORM`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-phpstorm-server/latest")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.PHPSTORM)
        assertThat(info.tooltip).isEqualTo("JetBrains PhpStorm (desktop)")
    }

    @Test
    fun `#create maps rider editor to RIDER`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-rider-server/latest")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.RIDER)
        assertThat(info.tooltip).isEqualTo("JetBrains Rider (desktop)")
    }

    @Test
    fun `#create maps rubymine editor to RUBYMINE`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-rubymine-server/latest")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.RUBYMINE)
        assertThat(info.tooltip).isEqualTo("JetBrains RubyMine (desktop)")
    }

    @Test
    fun `#create maps chemuxer editor to CHEMUXER`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-chemuxer-server/latest")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.CHEMUXER)
        assertThat(info.tooltip).isEqualTo("Chemuxer")
    }

    @Test
    fun `#create maps herdr editor to HERDR`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-herdr-server/latest")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.HERDR)
        assertThat(info.tooltip).isEqualTo("Herdr")
    }

    @Test
    fun `#create maps kiro editor to KIRO`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-kiro-server/latest")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.KIRO)
        assertThat(info.tooltip).isEqualTo("Kiro (desktop)")
    }

    @Test
    fun `#create maps web terminal editor to WEB_TERMINAL`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-web-terminal-server/latest")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.WEB_TERMINAL)
        assertThat(info.tooltip).isEqualTo("Web Terminal")
    }

    @Test
    fun `#create returns UNKNOWN for empty annotation`() {
        val dw = DevWorkspace(
            DevWorkspaceObjectMeta(name = "test", namespace = "ns", uid = "uid1", emptyMap(), emptyMap()),
            DevWorkspaceSpec(started = true),
            DevWorkspaceStatus(phase = "Running")
        )
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.UNKNOWN)
        assertThat(info.tooltip).isEqualTo("Unknown Editor")
    }

    @Test
    fun `#create returns UNKNOWN with fallback segment for partial path`() {
        val dw = createDevWorkspaceWithEditor("some/partial")
        val info = WorkspaceEditorInfoProvider.create(dw, emptyMap())
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.UNKNOWN)
        assertThat(info.tooltip).isEqualTo("partial")
    }

    private fun createDevWorkspaceWithEditor(cheEditor: String): DevWorkspace {
        return DevWorkspace(
            DevWorkspaceObjectMeta(
                name = "test-workspace",
                namespace = "test-namespace",
                uid = "test-uid",
                annotations = mapOf("che.eclipse.org/che-editor" to cheEditor),
                labels = emptyMap()
            ),
            DevWorkspaceSpec(started = true),
            DevWorkspaceStatus(phase = "Running")
        )
    }
}
