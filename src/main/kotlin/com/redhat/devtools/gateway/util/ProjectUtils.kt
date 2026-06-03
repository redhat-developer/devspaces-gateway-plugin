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
package com.redhat.devtools.gateway.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.ex.ProjectManagerEx

fun closeAllProjects() {
    ApplicationManager.getApplication().invokeLater(
        {
            val pm = ProjectManagerEx.getInstanceEx()
            for (project in pm.openProjects.toList()) {
                if (!project.isDisposed) {
                    pm.closeAndDispose(project)
                }
            }
        },
        ModalityState.nonModal()
    )
}
