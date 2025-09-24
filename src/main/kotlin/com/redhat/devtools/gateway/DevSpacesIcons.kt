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
import javax.swing.Icon

object DevSpacesIcons {

    val LOGO = IconLoader.getIcon("/icons/dev-spaces-logo.svg", javaClass)

    private val WORKSPACE_STARTING = IconLoader.getIcon("/icons/starting.svg", javaClass)
    private val WORKSPACE_STARTED = IconLoader.getIcon("/icons/started.svg", javaClass)
    private val WORKSPACE_STOPPED = IconLoader.getIcon("/icons/stopped.svg", javaClass)
    private val WORKSPACE_FAILED = IconLoader.getIcon("/icons/failed.svg", javaClass)

    fun getWorkspacePhaseIcon(phase: String): Icon? {
        /*
         * mimics what the web frontend is displaying.
         * @see [getStatusIcon.tsx](https://github.com/eclipse-che/che-dashboard/blob/main/packages/dashboard-frontend/src/components/Workspace/Status/getStatusIcon.tsx)
         */
        return when (phase) {
            "Starting" -> WORKSPACE_STARTING
            "Running" -> WORKSPACE_STARTED
            "Stopped" -> WORKSPACE_STOPPED
            "Failed", "Failing", "Error" -> WORKSPACE_FAILED
            else -> null
        }
    }

}
