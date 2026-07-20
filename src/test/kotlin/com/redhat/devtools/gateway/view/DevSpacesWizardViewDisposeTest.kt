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
package com.redhat.devtools.gateway.view

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DevSpacesWizardViewDisposeTest {

    @Test
    fun `canEnableNavigationButtons is false when steps were cleared`() {
        assertThat(canEnableNavigationButtons(stepCount = 0, currentStep = 0)).isFalse()
    }

    @Test
    fun `canEnableNavigationButtons is false when currentStep is out of range`() {
        assertThat(canEnableNavigationButtons(stepCount = 2, currentStep = 2)).isFalse()
        assertThat(canEnableNavigationButtons(stepCount = 2, currentStep = -1)).isFalse()
    }

    @Test
    fun `canEnableNavigationButtons is true for a valid step index`() {
        assertThat(canEnableNavigationButtons(stepCount = 2, currentStep = 0)).isTrue()
        assertThat(canEnableNavigationButtons(stepCount = 2, currentStep = 1)).isTrue()
    }
}
