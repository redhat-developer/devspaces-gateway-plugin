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

enum class WorkspaceEditorKind(val isConnectable: Boolean) {
    VSCODE(false),
    INTELLIJ_IDEA(true),
    PYCHARM(true),
    CLION(true),
    GOLAND(true),
    PHPSTORM(true),
    RIDER(true),
    RUBYMINE(true),
    WEBSTORM(true),
    CHEMUXER(false),
    HERDR(true),
    KIRO(true),
    TOOLBOX(false),
    WEB_TERMINAL(false),
    JETBRAINS(true),
    UNKNOWN(true)
}

data class WorkspaceEditorInfo(
    val kind: WorkspaceEditorKind,
    val tooltip: String,
)

object WorkspaceEditorInfoProvider {

    private val CHE_EDITOR_ID_REGEX = Regex("che-.*-server", RegexOption.IGNORE_CASE)

    private const val TOOLBOX_EDITOR_NAME = "jetbrains-sshd"
    private const val TOOLBOX_TOOLTIP = "JetBrains Toolbox App (unsupported editor)"

    private const val MIN_VERSIONED_SEGMENT_COUNT = 3
    private const val EDITOR_NAME_SEGMENT_INDEX = 1
    private const val CHE_EDITOR_PREFIX = "che-"

    /**
     * Resolves the workspace editor by precedence; the first matching rung wins:
     * 1. Toolbox-specific `che-editor` — outranks the annotated template because a Toolbox
     *    workspace can carry an `idea-server` template that this plugin cannot connect to.
     * 2. Annotated `che-editor-template` — the explicitly chosen template outranks
     *    editor-name matching.
     * 3. `che-editor` — the annotation names the editor directly.
     * 4. Template scan ([isJetBrainsEditor]) — infers a JetBrains editor from the workspace's
     *    templates when no annotation identified one.
     * 5. `UNKNOWN` — nothing identified the editor.
     *
     * Toolbox is identified exclusively by the `che-editor` annotation: no template-only
     * Toolbox workspace exists in the field, so the template rungs deliberately never resolve
     * `TOOLBOX`. A template carrying an `idea-server` component is still reported as connectable
     * `JETBRAINS`, which is why the Toolbox rung must run before the template rungs.
     */
    fun create(
        devWorkspace: DevWorkspace,
        templateMap: Map<String, List<DevWorkspaceTemplate>>
    ): WorkspaceEditorInfo {
        val editorTemplateAnnotation = Utils.getValue(
            devWorkspace.annotations,
            arrayOf("che.eclipse.org/che-editor-template")
        ) as? String
        val editorAnnotation = Utils.getValue(
            devWorkspace.annotations,
            arrayOf("che.eclipse.org/che-editor")
        ) as? String
        val editorPath = editorAnnotation
            ?.takeIf { it.isNotBlank() }
            ?.let(CheEditorPath::parse)

        // Toolbox wins over the editor template: a Toolbox workspace can carry an
        // idea-server template, but this plugin cannot connect to it.
        if (editorPath != null) {
            createFromToolboxEditor(editorPath)?.let { return it }
        }

        if (!editorTemplateAnnotation.isNullOrBlank()) {
            createFromEditorTemplateAnnotation(editorTemplateAnnotation, devWorkspace, templateMap)?.let { return it }
        }

        if (editorPath != null) {
            createFromEditorAnnotation(editorPath).let { return it }
        }

        if (editorTemplateAnnotation.isNullOrBlank()
            && isJetBrainsEditor(devWorkspace, templateMap)) {
            return WorkspaceEditorInfo(WorkspaceEditorKind.JETBRAINS, "JetBrains")
        }

        return WorkspaceEditorInfo(WorkspaceEditorKind.UNKNOWN, "Unknown Editor")
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
        return template.components.any { component: Map<String, Any> ->
            val volume = component["volume"] as? Map<*, *>
            // Check 'volume.name' first (v1alpha1)
            val name = volume?.get("name") as? String
            // fallback to top-level 'name' (v1alpha2)
                ?: component["name"] as? String
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
     * This is intentionally scoped to JetBrains editor resolution: Toolbox is identified only
     * by the `che-editor` annotation, never by a template, so this rung cannot return
     * `TOOLBOX`.
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

    private fun createFromEditorAnnotation(editorPath: CheEditorPath): WorkspaceEditorInfo {
        val editorName = editorPath.editorName
        if (editorName != null) {
            createFromEditorName(editorName)?.let { return it }
        }

        if (editorPath.hasCheEditorId()) {
            return WorkspaceEditorInfo(WorkspaceEditorKind.JETBRAINS, "JetBrains")
        }

        return WorkspaceEditorInfo(
            WorkspaceEditorKind.UNKNOWN,
            editorPath.fallbackSegment ?: "Unknown Editor"
        )
    }

    /**
     * Detects the JetBrains Toolbox App editor from a parsed `che-editor` value.
     *
     * `jetbrains-sshd` is exclusive to Toolbox workspaces in the field, so the segment is
     * matched by content instead of by position: registry-prefixed values such as
     * `quay.io/che-incubator/jetbrains-sshd/next` would otherwise be misread as the
     * publisher/organisation segment. The editor-name check runs before generic
     * editor-name matching because Toolbox detection must outrank it.
     */
    private fun createFromToolboxEditor(editorPath: CheEditorPath): WorkspaceEditorInfo? {
        return if (editorPath.isToolboxEditor) {
            WorkspaceEditorInfo(WorkspaceEditorKind.TOOLBOX, TOOLBOX_TOOLTIP)
        } else {
            null
        }
    }

    /**
     * Parses a `che-editor` value such as `registry/editor/version`, `editor/version`, or
     * `editor` into a single set of editor-coordinate accessors shared by every consumer,
     * so Toolbox detection and editor-name matching cannot disagree.
     */
    private class CheEditorPath private constructor(segments: List<String>) {

        private val parts = segments.filter { it.isNotBlank() }

        val isToolboxEditor: Boolean =
            parts.any { it.equals(TOOLBOX_EDITOR_NAME, ignoreCase = true) }

        val editorName: String? = resolveEditorName()

        val fallbackSegment: String?
            get() = parts.lastOrNull()

        fun hasCheEditorId(): Boolean = parts.any { CHE_EDITOR_ID_REGEX.matches(it) }

        private fun resolveEditorName(): String? =
            if (parts.size >= MIN_VERSIONED_SEGMENT_COUNT) {
                parts[EDITOR_NAME_SEGMENT_INDEX]
            } else {
                parts.firstOrNull(::isEditorNameCandidate)
            }

        private fun isEditorNameCandidate(segment: String): Boolean =
            segment.equals(TOOLBOX_EDITOR_NAME, ignoreCase = true) ||
                    CHE_EDITOR_ID_REGEX.matches(segment) ||
                    segment.startsWith(CHE_EDITOR_PREFIX, ignoreCase = true)

        companion object {
            fun parse(annotation: String): CheEditorPath =
                CheEditorPath(annotation.split('/'))
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
