package com.redhat.devtools.gateway.view.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon

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

    /**
     * Shows a dialog on the EDT and returns the selected button index.
     *
     * @param message The message to display
     * @param title The dialog title
     * @param options Array of button labels
     * @param defaultOptionIndex The index of the default selected button
     * @param icon The icon to display (optional)
     * @return The index of the selected button
     */
    fun showInEdt(
        message: String,
        title: String,
        options: Array<String>,
        defaultOptionIndex: Int = 0,
        icon: Icon? = Messages.getWarningIcon()
    ): Int {
        val result = AtomicInteger(-1)
        ApplicationManager.getApplication().invokeAndWait {
            result.set(
                Messages.showDialog(
                    message,
                    title,
                    options,
                    defaultOptionIndex,
                    icon
                )
            )
        }
        return result.get()
    }

    /**
     * Shows a dialog and returns true if the user selected the specified option index.
     *
     * @param message The message to display
     * @param title The dialog title
     * @param buttons Array of button labels
     * @param confirmOptionIndex The index of the button that should return true
     * @param defaultOptionIndex The index of the default selected button
     * @return true if the user selected the confirmOptionIndex, false otherwise
     */
    fun confirm(
        message: String,
        title: String,
        buttons: Array<String>,
        confirmOptionIndex: Int,
        defaultOptionIndex: Int = 0
    ): Boolean {
        return showInEdt(
            message,
            title,
            buttons,
            defaultOptionIndex,
            Messages.getWarningIcon()
        ) == confirmOptionIndex
    }

    fun error(
        message: String,
        title: String,
        buttons: Array<String>,
        confirmOptionIndex: Int,
        defaultOptionIndex: Int = 0
    ): Boolean {
        return showInEdt(
            message,
            title,
            buttons,
            defaultOptionIndex,
            Messages.getErrorIcon()
        ) == confirmOptionIndex
    }

    /**
     * Shows a dialog for when the workspace IDE is not responding.
     *
     * @return true if the user wants to restart the Pod, false if they want to cancel the connection
     */
    fun ideNotResponding(): Boolean {
        return confirm(
            message = "The workspace IDE is not responding properly.\n" +
                    "Would you like to try restarting the Pod or cancel the connection?",
            title = "Cannot Connect to workspace IDE",
            buttons = arrayOf("Cancel Connection", "Restart Pod and try again"),
            confirmOptionIndex = 1,
            defaultOptionIndex = 0
        )
    }

}