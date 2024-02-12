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
package com.github.devspaces.gateway.view

import com.github.devspaces.gateway.openshift.DevSpacesContext
import com.github.devspaces.gateway.openshift.DevSpacesGatewayConnection
import com.github.devspaces.gateway.view.steps.DevSpacesDevWorkspaceSelectingStepView
import com.github.devspaces.gateway.view.steps.DevSpacesOpenShiftConnectionStepView
import com.github.devspaces.gateway.view.steps.DevSpacesWizardStep
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

class DevSpacesWizardView(private val devSpacesContext: DevSpacesContext) : BorderLayoutPanel(), Disposable {
    private var steps = arrayListOf<DevSpacesWizardStep>()
    private var currentStep = 0

    private var previousButton = JButton()
    private var nextButton = JButton()

    init {
        steps.add(DevSpacesOpenShiftConnectionStepView(devSpacesContext))
        steps.add(DevSpacesDevWorkspaceSelectingStepView(devSpacesContext))

        addToBottom(createButtons())
        applyStep()
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
            border = JBUI.Borders.empty()
        }
    }

    private fun nextStep() {
        if (!steps[currentStep].onNext()) return

        if (isLastStep()) {
            DevSpacesGatewayConnection(devSpacesContext).connect()
        } else {
            remove(steps[currentStep].component)
            updateUI()

            currentStep++
            applyStep()
        }
    }

    private fun previousStep() {
        if (!steps[currentStep].onPrevious()) return

        remove(steps[currentStep].component)
        updateUI()

        currentStep--
        if (isFirstStep()) GatewayUI.getInstance().reset()
        applyStep()
    }

    private fun applyStep() {
        steps[currentStep].apply {
            addToCenter(component)
            nextButton.text = nextActionText
            previousButton.text = previousActionText
            onInit()
        }
    }

    private fun isFirstStep(): Boolean {
        return currentStep == 0
    }

    private fun isLastStep(): Boolean {
        return currentStep == steps.size - 1
    }
}