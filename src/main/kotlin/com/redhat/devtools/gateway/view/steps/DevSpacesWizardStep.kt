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
package com.redhat.devtools.gateway.view.steps

import com.intellij.openapi.ui.DialogPanel

sealed interface DevSpacesWizardStep {
    val nextActionText: String
    val previousActionText: String
    val component: DialogPanel

    fun isNextEnabled(): Boolean {
        return true
    }

    fun onInit()

    fun onPrevious(): Boolean

    fun onNext(): Boolean
}