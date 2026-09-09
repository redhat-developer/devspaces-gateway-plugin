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

internal data class NamespaceWatchDecision(
    val namespace: String,
    val resourceVersion: String,
    val jetbrainsWorkspaceCount: Int,
)

fun WorkspaceEditorKind.isJetBrainsFamily(): Boolean = when (this) {
    WorkspaceEditorKind.INTELLIJ_IDEA,
    WorkspaceEditorKind.PYCHARM,
    WorkspaceEditorKind.CLION,
    WorkspaceEditorKind.GOLAND,
    WorkspaceEditorKind.PHPSTORM,
    WorkspaceEditorKind.RIDER,
    WorkspaceEditorKind.RUBYMINE,
    WorkspaceEditorKind.WEBSTORM,
    WorkspaceEditorKind.HERDR,
    WorkspaceEditorKind.KIRO,
    WorkspaceEditorKind.JETBRAINS -> true
    else -> false
}

private val CHE_EDITOR_ID_REGEX = Regex("che-.*-server", RegexOption.IGNORE_CASE)

object WorkspaceEditorInfoProvider {

    fun create(
        devWorkspace: DevWorkspace,
        templateMap: Map<String, List<DevWorkspaceTemplate>>
    ): WorkspaceEditorInfo {
        val editorTemplateAnnotation = Utils.getValue(
            devWorkspace.annotations,
            arrayOf("che.eclipse.org/che-editor-template")) as? String
        if (!editorTemplateAnnotation.isNullOrBlank()) {
            createFromEditorTemplateAnnotation(editorTemplateAnnotation, devWorkspace, templateMap)?.let { return it }
        }
        val editorAnnotation = Utils.getValue(
            devWorkspace.annotations,
            arrayOf("che.eclipse.org/che-editor")) as? String
        if (!editorAnnotation.isNullOrBlank()) {
            createFromEditorAnnotation(editorAnnotation).let { return it }
        }
        if (editorTemplateAnnotation.isNullOrBlank() && isJetBrainsEditor(devWorkspace, templateMap)) {
            return WorkspaceEditorInfo(WorkspaceEditorKind.JETBRAINS, "JetBrains")
        }
        return WorkspaceEditorInfo(WorkspaceEditorKind.UNKNOWN, "Unknown Editor")
    }

    fun isJetBrainsWorkspace(
        devWorkspace: DevWorkspace,
        templateMap: Map<String, List<DevWorkspaceTemplate>>
    ): Boolean {
        return create(devWorkspace, templateMap).kind.isJetBrainsFamily()
    }

    internal fun namespaceWatchResourceVersion(
        namespace: String,
        items: List<DevWorkspaceListItem>,
        templates: Map<String, List<DevWorkspaceTemplate>>,
        resourceVersion: String?
    ): NamespaceWatchDecision? {
        val jetbrainsCount = items.count { isJetBrainsWorkspace(it.workspace, templates) }
        return if (jetbrainsCount > 0 && resourceVersion != null)
            NamespaceWatchDecision(namespace, resourceVersion, jetbrainsCount)
        else null
    }

    internal fun isJetBrainsEditor(
        devWorkspace: DevWorkspace,
        templateMap: Map<String, List<DevWorkspaceTemplate>>
    ): Boolean {
        val templates = templateMap[devWorkspace.uid]
            ?: return false
        return templates.any { template ->
            hasIdeaServerComponent(template)
        }
    }

    private fun hasIdeaServerComponent(template: DevWorkspaceTemplate): Boolean {
        @Suppress("UNCHECKED_CAST")
        val components = template.components as? List<Any>
            ?: return false
        return components.any { component: Any ->
            val map = component as? Map<*, *>
                ?: return@any false
            val volume = map["volume"] as? Map<*, *>
            // Check 'volume.name' first (v1alpha1)
            val name = volume?.get("name") as? String
            // fallback to top-level 'name' (v1alpha2)
                ?: map["name"] as? String
            name.equals("idea-server", ignoreCase = true)
        }
    }

    /**
     * Resolves the editor from the workspace's editor template annotation.
     *
     * Only JetBrains editors are resolved here, via an `idea-server` component check on the
     * annotated template. Returns `null` when the template is not found or lacks `idea-server`,
     * in which case the caller falls through to the `che-editor` annotation only; the
     * `isJetBrainsEditor` template scan is skipped, so unrelated templates carrying an
     * `idea-server` component cannot override the annotated template choice.
     * This is intentionally scoped to the JetBrains namespace-watch use case.
     *
     * @param editorTemplateAnnotation the value of the editor template annotation
     * @param devWorkspace the workspace owning the annotation
     * @param templateMap map of owner UID to templates for the workspace
     * @return a JetBrains [WorkspaceEditorInfo], or `null` so the caller falls through
     */
    private fun createFromEditorTemplateAnnotation(
        editorTemplateAnnotation: String,
        devWorkspace: DevWorkspace,
        templateMap: Map<String, List<DevWorkspaceTemplate>>
    ): WorkspaceEditorInfo? {
        val annotatedTemplate = templateMap[devWorkspace.uid]?.firstOrNull {
            it.name.equals(editorTemplateAnnotation, ignoreCase = true)
        }
        if (annotatedTemplate != null
            && hasIdeaServerComponent(annotatedTemplate)) {
            return WorkspaceEditorInfo(WorkspaceEditorKind.JETBRAINS, "JetBrains")
        }
        return null
    }

    private fun createFromEditorAnnotation(cheEditor: String): WorkspaceEditorInfo {
        val cheEditorSegments = cheEditor.split("/")
        val editorName = extractEditorName(cheEditorSegments)
        if (editorName != null) {
            createFromEditorName(editorName)?.let { return it }
        }
        if (cheEditorSegments.any { CHE_EDITOR_ID_REGEX.matches(it) }) {
            return WorkspaceEditorInfo(WorkspaceEditorKind.JETBRAINS, "JetBrains")
        }
        val fallbackSegment = cheEditorSegments.lastOrNull { it.isNotBlank() }
        return WorkspaceEditorInfo(WorkspaceEditorKind.UNKNOWN, fallbackSegment ?: "Unknown Editor")
    }

    private fun extractEditorName(cheEditorSegments: List<String>): String? {
        val parts = cheEditorSegments.filter { it.isNotBlank() }
        if (parts.size >= 3) {
            return parts[1]
        }
        return parts.firstOrNull {
            CHE_EDITOR_ID_REGEX.matches(it)
                || it.startsWith("che-", ignoreCase = true)
        }
    }

    private fun createFromEditorName(editorName: String): WorkspaceEditorInfo? {
        val lowercase = editorName.lowercase()
        return when {
            lowercase.contains("che-code") ->
                WorkspaceEditorInfo(WorkspaceEditorKind.VSCODE, "VS Code - Open Source")
            lowercase.contains("che-idea") ->
                WorkspaceEditorInfo(WorkspaceEditorKind.INTELLIJ_IDEA, "IntelliJ IDEA Ultimate (desktop)")
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
