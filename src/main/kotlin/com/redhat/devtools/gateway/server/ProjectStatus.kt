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

package com.redhat.devtools.gateway.server

data class ProjectStatus(
    val joinLink: String,
    val httpLink: String,
    val gatewayLink: String,
    val appVersion: String,
    val runtimeVersion: String,
    val projects: Array<ProjectInfo>
) {
    companion object {
        fun empty(): ProjectStatus {
            return ProjectStatus("", "", "", "", "", emptyArray())
        }
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProjectStatus

        if (joinLink != other.joinLink) return false
        if (httpLink != other.httpLink) return false
        if (gatewayLink != other.gatewayLink) return false
        if (appVersion != other.appVersion) return false
        if (runtimeVersion != other.runtimeVersion) return false
        if (!projects.contentEquals(other.projects)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = joinLink.hashCode()
        result = 31 * result + httpLink.hashCode()
        result = 31 * result + gatewayLink.hashCode()
        result = 31 * result + appVersion.hashCode()
        result = 31 * result + runtimeVersion.hashCode()
        result = 31 * result + projects.contentHashCode()
        return result
    }
}

