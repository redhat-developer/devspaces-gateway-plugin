package com.redhat.devtools.gateway.view.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages

object Dialogs {

    fun error(message: String, title: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showMessageDialog(
                message,
                title,
                Messages.getErrorIcon(),
            )
        }
    }
}