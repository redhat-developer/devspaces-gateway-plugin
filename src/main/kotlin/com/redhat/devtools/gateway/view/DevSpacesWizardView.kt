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
package com.redhat.devtools.gateway.view

import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.view.steps.DevSpacesWorkspacesStepView
import com.redhat.devtools.gateway.view.steps.DevSpacesServerStepView
import com.redhat.devtools.gateway.view.steps.DevSpacesWizardStep
import com.intellij.openapi.Disposable
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import com.jetbrains.gateway.api.GatewayUI
import java.awt.Component
import javax.swing.JButton

class DevSpacesWizardView(devSpacesContext: DevSpacesContext) : BorderLayoutPanel(), Disposable {
    private var steps = arrayListOf<DevSpacesWizardStep>()
    private var currentStep = 0

    private var previousButton = JButton()
    private var nextButton = JButton()

    init {
        steps.add(DevSpacesServerStepView(devSpacesContext))
        steps.add(DevSpacesWorkspacesStepView(devSpacesContext))

        addToBottom(createButtons())
        applyStep(0)
    }

    override fun dispose() {
        steps.clear()
    }

    private fun createButtons(): Component {
        return panel {
            separator(background = WelcomeScreenUIManager.getSeparatorColor())
            row {
                label("").resizableColumn().align(AlignX.FILL).gap(RightGap.SMALL)

                previousButton = button("") {
                    previousStep()
                }.align(AlignX.RIGHT).gap(RightGap.SMALL).applyToComponent {
                    background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
                }.component

                nextButton = button("") {
                    nextStep()
                }.align(AlignX.RIGHT).applyToComponent {
                    background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
                }.component
            }
        }.apply {
            background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
            border = JBUI.Borders.empty(4)
        }
    }

    private fun nextStep() {
        if (steps[currentStep].onNext()) applyStep(+1)
    }

    private fun previousStep() {
        if (!steps[currentStep].onPrevious()) return

        if (isFirstStep()) {
            GatewayUI.getInstance().reset()
        } else {
            applyStep(-1)
        }
    }

    private fun applyStep(shift: Int) {
        remove(steps[currentStep].component)
        updateUI()

        currentStep += shift
        steps[currentStep].apply {
            addToCenter(component)
            nextButton.text = nextActionText
            previousButton.text = previousActionText
            onInit()
        }

        refreshNextButtonState()
    }

    private fun refreshNextButtonState() {
        val step = steps[currentStep]
        nextButton.isEnabled = step.isNextEnabled()
    }

    private fun isFirstStep(): Boolean {
        return currentStep == 0
    }

    private fun isLastStep(): Boolean {
        return currentStep == steps.size - 1
    }
}