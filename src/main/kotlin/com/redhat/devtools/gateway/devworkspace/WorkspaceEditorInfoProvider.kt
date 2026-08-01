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

import com.redhat.devtools.gateway.openshift.Utils

enum class WorkspaceEditorKind {
    VSCODE,
    INTELLIJ_IDEA,
    PYCHARM,
    CLION,
    GOLAND,
    PHPSTORM,
    RIDER,
    RUBYMINE,
    WEBSTORM,
    CHEMUXER,
    HERDR,
    KIRO,
    WEB_TERMINAL,
    JETBRAINS,
    UNKNOWN,
}

data class WorkspaceEditorInfo(
    val kind: WorkspaceEditorKind,
    val tooltip: String,
)

private val CHE_EDITOR_ID_REGEX = Regex("che-.*-server", RegexOption.IGNORE_CASE)

object WorkspaceEditorInfoProvider {

    fun create(
        devWorkspace: DevWorkspace,
        templateMap: Map<String, List<DevWorkspaceTemplate>>
    ): WorkspaceEditorInfo {
        val cheEditor = Utils.getValue(devWorkspace.annotations, arrayOf("che.eclipse.org/che-editor")) as? String
        if (!cheEditor.isNullOrBlank()) {
            return createFromAnnotation(cheEditor)
        }
        if (isJetBrainsEditor(devWorkspace, templateMap)) {
            return WorkspaceEditorInfo(WorkspaceEditorKind.JETBRAINS, "JetBrains")
        }
        return WorkspaceEditorInfo(WorkspaceEditorKind.UNKNOWN, "Unknown Editor")
    }

    fun isJetBrainsEditor(
        devWorkspace: DevWorkspace,
        templateMap: Map<String, List<DevWorkspaceTemplate>>
    ): Boolean {
        // DevWorkspace Template check
        val templates = templateMap[devWorkspace.uid] ?: return false
        return templates.any { template ->
            @Suppress("UNCHECKED_CAST")
            val components = template.components as? List<Any> ?: return@any false
            components.any { component: Any ->
                val map = component as? Map<*, *> ?: return@any false
                val volume = map["volume"] as? Map<*, *>
                // Check 'volume.name' first (v1alpha1), fallback to top-level 'name' (v1alpha2)
                val name = volume?.get("name") as? String ?: map["name"] as? String
                name.equals("idea-server", ignoreCase = true)
            }
        }
    }

    private fun createFromAnnotation(cheEditor: String): WorkspaceEditorInfo {
        val editorName = extractEditorName(cheEditor)
        if (editorName != null) {
            createFromEditorName(editorName)?.let { return it }
        }
        if (cheEditor.split("/").any { CHE_EDITOR_ID_REGEX.matches(it) }) {
            return WorkspaceEditorInfo(WorkspaceEditorKind.JETBRAINS, "JetBrains")
        }
        val fallbackSegment = cheEditor.split("/").lastOrNull { it.isNotBlank() }
        return WorkspaceEditorInfo(WorkspaceEditorKind.UNKNOWN, fallbackSegment ?: "Unknown Editor")
    }

    private fun extractEditorName(cheEditor: String): String? {
        val parts = cheEditor.split("/").filter { it.isNotBlank() }
        if (parts.size >= 3) {
            return parts[1]
        }
        return parts.firstOrNull { CHE_EDITOR_ID_REGEX.matches(it) || it.startsWith("che-", ignoreCase = true) }
    }

    private fun createFromEditorName(editorName: String): WorkspaceEditorInfo? {
        val lowercase = editorName.lowercase()
        return when {
            lowercase.contains("che-code") -> WorkspaceEditorInfo(WorkspaceEditorKind.VSCODE, "VS Code - Open Source")
            lowercase.contains("che-idea") -> WorkspaceEditorInfo(
                WorkspaceEditorKind.INTELLIJ_IDEA,
                "IntelliJ IDEA Ultimate (desktop)"
            )
            lowercase.contains("che-pycharm") ->
                WorkspaceEditorInfo(WorkspaceEditorKind.PYCHARM, "PyCharm")
            lowercase.contains("che-clion") ->
                WorkspaceEditorInfo(WorkspaceEditorKind.CLION, "JetBrains CLion (desktop)")
            lowercase.contains("che-goland") ->
                WorkspaceEditorInfo(WorkspaceEditorKind.GOLAND, "JetBrains GoLand (desktop)")
            lowercase.contains("che-phpstorm") ->
                WorkspaceEditorInfo(WorkspaceEditorKind.PHPSTORM, "JetBrains PhpStorm (desktop)")
            lowercase.contains("che-rider") ->
                WorkspaceEditorInfo(WorkspaceEditorKind.RIDER, "JetBrains Rider (desktop)")
            lowercase.contains("che-rubymine") ->
                WorkspaceEditorInfo(WorkspaceEditorKind.RUBYMINE, "JetBrains RubyMine (desktop)")
            lowercase.contains("che-webstorm") ->
                WorkspaceEditorInfo(WorkspaceEditorKind.WEBSTORM, "JetBrains WebStorm (desktop)")
            lowercase.contains("che-chemuxer") ->
                WorkspaceEditorInfo(WorkspaceEditorKind.CHEMUXER, "Chemuxer")
            lowercase.contains("che-herdr") ->
                WorkspaceEditorInfo(WorkspaceEditorKind.HERDR, "Herdr")
            lowercase.contains("che-kiro") ->
                WorkspaceEditorInfo(WorkspaceEditorKind.KIRO, "Kiro (desktop)")
            lowercase.contains("che-web-terminal") ->
                WorkspaceEditorInfo(WorkspaceEditorKind.WEB_TERMINAL, "Web Terminal")
            CHE_EDITOR_ID_REGEX.matches(editorName) ->
                WorkspaceEditorInfo(WorkspaceEditorKind.JETBRAINS, "JetBrains")
            else -> null
        }
    }
}
