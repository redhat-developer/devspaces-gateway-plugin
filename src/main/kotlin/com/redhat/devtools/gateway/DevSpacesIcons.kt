/*
 * Copyright (c) 2025 Red Hat, Inc.
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

import com.intellij.openapi.util.IconLoader
import com.redhat.devtools.gateway.devworkspace.WorkspaceEditorKind
import javax.swing.Icon

object DevSpacesIcons {

    val LOGO = IconLoader.getIcon("/icons/dev-spaces-logo.svg", javaClass)

    private val WORKSPACE_STARTING = IconLoader.getIcon("/icons/starting.svg", javaClass)
    private val WORKSPACE_STARTED = IconLoader.getIcon("/icons/started.svg", javaClass)
    private val WORKSPACE_STOPPING = IconLoader.getIcon("/icons/stopping.svg", javaClass)
    private val WORKSPACE_STOPPED = IconLoader.getIcon("/icons/stopped.svg", javaClass)
    private val WORKSPACE_TERMINATING = IconLoader.getIcon("/icons/stopping.svg", javaClass)
    private val WORKSPACE_FAILED = IconLoader.getIcon("/icons/failed.svg", javaClass)

    private val EDITOR_VSCODE = IconLoader.getIcon("/icons/editors/vscode.svg", javaClass)
    private val EDITOR_INTELLIJ_IDEA = IconLoader.getIcon("/icons/editors/intellij-idea.svg", javaClass)
    private val EDITOR_JETBRAINS = IconLoader.getIcon("/icons/editors/jetbrains.svg", javaClass)
    private val EDITOR_PYCHARM = IconLoader.getIcon("/icons/editors/pycharm.svg", javaClass)
    private val EDITOR_CLION = IconLoader.getIcon("/icons/editors/clion.svg", javaClass)
    private val EDITOR_GOLAND = IconLoader.getIcon("/icons/editors/goland.svg", javaClass)
    private val EDITOR_PHPSTORM = IconLoader.getIcon("/icons/editors/phpstorm.svg", javaClass)
    private val EDITOR_RIDER = IconLoader.getIcon("/icons/editors/rider.svg", javaClass)
    private val EDITOR_RUBYMINE = IconLoader.getIcon("/icons/editors/rubymine.svg", javaClass)
    private val EDITOR_WEBSTORM = IconLoader.getIcon("/icons/editors/webstorm.svg", javaClass)
    private val EDITOR_CHEMUXER = IconLoader.getIcon("/icons/editors/chemuxer.svg", javaClass)
    private val EDITOR_HERDR = IconLoader.getIcon("/icons/editors/herdr.svg", javaClass)
    private val EDITOR_KIRO = IconLoader.getIcon("/icons/editors/kiro.svg", javaClass)
    private val EDITOR_WEB_TERMINAL = IconLoader.getIcon("/icons/editors/web-terminal.svg", javaClass)
    private val EDITOR_UNKNOWN = IconLoader.getIcon("/icons/editors/unknown.svg", javaClass)

    fun getWorkspacePhaseIcon(phase: String): Icon? {
        /*
         * mimics what the web frontend is displaying.
         * @see [getStatusIcon.tsx](https://github.com/eclipse-che/che-dashboard/blob/main/packages/dashboard-frontend/src/components/Workspace/Status/getStatusIcon.tsx)
         */
        return when (phase) {
            "Starting" -> WORKSPACE_STARTING
            "Running" -> WORKSPACE_STARTED
            "Stopped" -> WORKSPACE_STOPPED
            "Stopping" -> WORKSPACE_STOPPING
            "Terminating" -> WORKSPACE_TERMINATING
            "Failed", "Failing", "Error" -> WORKSPACE_FAILED
            else -> null
        }
    }

    fun getEditorIcon(kind: WorkspaceEditorKind): Icon {
        return when (kind) {
            WorkspaceEditorKind.VSCODE -> EDITOR_VSCODE
            WorkspaceEditorKind.INTELLIJ_IDEA -> EDITOR_INTELLIJ_IDEA
            WorkspaceEditorKind.JETBRAINS -> EDITOR_JETBRAINS
            WorkspaceEditorKind.PYCHARM -> EDITOR_PYCHARM
            WorkspaceEditorKind.CLION -> EDITOR_CLION
            WorkspaceEditorKind.GOLAND -> EDITOR_GOLAND
            WorkspaceEditorKind.PHPSTORM -> EDITOR_PHPSTORM
            WorkspaceEditorKind.RIDER -> EDITOR_RIDER
            WorkspaceEditorKind.RUBYMINE -> EDITOR_RUBYMINE
            WorkspaceEditorKind.WEBSTORM -> EDITOR_WEBSTORM
            WorkspaceEditorKind.CHEMUXER -> EDITOR_CHEMUXER
            WorkspaceEditorKind.HERDR -> EDITOR_HERDR
            WorkspaceEditorKind.KIRO -> EDITOR_KIRO
            WorkspaceEditorKind.WEB_TERMINAL -> EDITOR_WEB_TERMINAL
            WorkspaceEditorKind.UNKNOWN -> EDITOR_UNKNOWN
        }
    }

}
