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
    val STARTED = IconLoader.getIcon("/icons/started.svg", javaClass)
    val STOPPED = IconLoader.getIcon("/icons/stopped.svg", javaClass)

    fun getWorkspacePhaseIcon(phase: String): Icon? {
        return when (phase) {
            "Running" -> DevSpacesIcons.STARTED
            "Stopped" -> DevSpacesIcons.STOPPED
            else -> null
        }
    }

}
