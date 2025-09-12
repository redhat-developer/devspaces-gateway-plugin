package com.redhat.devtools.gateway.view.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Dialogs {

    suspend fun error(message: String, title: String) {
        withContext(Dispatchers.Main) {
            Messages.showMessageDialog(
                message,
                title,
                Messages.getErrorIcon(),
            )
        }
    }

}