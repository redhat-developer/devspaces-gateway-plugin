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
import com.redhat.devtools.gateway.view.steps.WizardAsyncWork
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
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
        steps.add(
            DevSpacesServerStepView(
                devSpacesContext = devSpacesContext,
                enableNextButton = { enableNavigationButtons() },
                triggerNextAction = { nextStep() },
            )
        )
        steps.add(
            DevSpacesWorkspacesStepView(devSpacesContext)
            { enableNavigationButtons() }
                .also {
                    Disposer.register(this, it)
                }
        )
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
        val step = steps[currentStep]
        if (!step.isNavigationEnabled()) return

        val asyncWork = step.startAsyncNext()
        if (asyncWork != null) {
            runAsyncNext(asyncWork)
            return
        }
        if (step.onNext()) {
            applyStep(+1)
        }
    }

    private fun runAsyncNext(work: WizardAsyncWork) {
        enableNavigationButtons(false)
        WizardAsyncWork.execute(work) { advance ->
            enableNavigationButtons(true)
            if (advance) {
                applyStep(+1)
            }
        }
    }

    private fun previousStep() {
        WizardAsyncWork.invalidatePending()
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

        enableNavigationButtons()
    }

    private fun enableNavigationButtons(enabled: Boolean? = null) {
        val step = steps[currentStep]
        val navigationEnabled = enabled ?: step.isNavigationEnabled()
        previousButton.isEnabled = navigationEnabled
        nextButton.isEnabled = navigationEnabled && step.isNextEnabled()
    }

    private fun isFirstStep(): Boolean {
        return currentStep == 0
    }

    private fun isLastStep(): Boolean {
        return currentStep == steps.size - 1
    }
}