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
package com.redhat.devtools.gateway

import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.openshift.Cluster
import io.kubernetes.client.openapi.ApiClient

class DevSpacesContext {
    lateinit var client: ApiClient
    var cluster: Cluster? = null
    lateinit var devWorkspace: DevWorkspace
    var activeWorkspaces = mutableSetOf<DevWorkspace>()

    /**
     * Returns `true` if this context has client
     *
     * @return true if this context has a client. False otherwise
     */
    fun hasClient(): Boolean {
        return ::client.isInitialized
                && client.basePath.isNotBlank()
    }

    fun hasWorkspace(): Boolean {
        return ::devWorkspace.isInitialized
    }

    fun addWorkspace(workspace: DevWorkspace) {
        synchronized(activeWorkspaces) {
            if (activeWorkspaces.any { sameWorkspace(it, workspace) }) {
                return
            }
            activeWorkspaces.add(workspace)
            thisLogger().info(
                "Tracking active connection to workspace ${workspace.namespace}/${workspace.name}"
            )
        }
    }

    fun removeWorkspace(currentWorkspace: DevWorkspace) {
        synchronized(activeWorkspaces) {
            val removed = activeWorkspaces.removeAll { sameWorkspace(it, currentWorkspace) }
            if (removed) {
                thisLogger().info(
                    "Stopped tracking connection to workspace " +
                        "${currentWorkspace.namespace}/${currentWorkspace.name}"
                )
            }
        }
    }

    fun isWorkspaceActive(workspace: DevWorkspace): Boolean {
        synchronized(activeWorkspaces) {
            return activeWorkspaces.any { sameWorkspace(it, workspace) }
        }
    }

    private fun sameWorkspace(a: DevWorkspace, b: DevWorkspace): Boolean =
        a.namespace == b.namespace && a.name == b.name
}
