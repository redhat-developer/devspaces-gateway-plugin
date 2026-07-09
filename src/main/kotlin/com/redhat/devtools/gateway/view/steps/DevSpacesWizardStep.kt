/*
 * Copyright (c) 2024-2026 Red Hat, Inc.
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

    fun onInit()

    fun onPrevious(): Boolean

    fun onNext(): Boolean

    /**
     * Optional background work before advancing to the next step.
     * When non-null, the wizard runs it and advances when [WizardAsyncWork] reports success.
     */
    fun startAsyncNext(): WizardAsyncWork? = null

    /**
     * Determines if the next button should be enabled.
     * Default implementation returns true.
     */
    fun isNextEnabled(): Boolean = true

    /**
     * Whether Previous/Next navigation is allowed. Disabled while async work runs.
     */
    fun isNavigationEnabled(): Boolean = true

    fun onDispose() {}
}