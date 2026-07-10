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
            if (activeWorkspaces.contains(workspace)) {
                return
            }
            activeWorkspaces.add(workspace)
        }
    }

    fun removeWorkspace(currentWorkspace: DevWorkspace) {
        synchronized(activeWorkspaces) {
            activeWorkspaces.remove(currentWorkspace)
        }
    }

}
