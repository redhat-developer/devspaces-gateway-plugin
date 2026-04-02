/*
 * Copyright (c) 2025 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.terminal

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo

/**
 * Utility for opening a local terminal window connected to a workspace pod.
 */
object WorkspaceTerminal {

    private val logger = thisLogger()

    /**
     * Opens a local terminal connected to the specified workspace pod.
     *
     * @param namespace The Kubernetes namespace
     * @param workspaceName The workspace name (used as pattern to find the pod)
     * @return true if the terminal was opened successfully, false otherwise
     */
    fun open(namespace: String, workspaceName: String): Boolean {
        return try {
            logger.info("Opening local terminal for workspace: $workspaceName in namespace: $namespace")

            when {
                SystemInfo.isMac -> openMacTerminal(namespace, workspaceName)
                SystemInfo.isWindows -> openWindowsTerminal(namespace, workspaceName)
                SystemInfo.isLinux -> openLinuxTerminal(namespace, workspaceName)
                else -> {
                    logger.error("Unsupported operating system")
                    return false
                }
            }

            logger.info("Successfully opened local terminal for workspace: $workspaceName")
            true
        } catch (e: Exception) {
            logger.error("Failed to open local terminal for workspace: $workspaceName", e)
            false
        }
    }

    private fun buildShellScript(namespace: String, workspaceName: String): String {
        // Use label selector to find the pod associated with the workspace
        // DevWorkspace pods have the label controller.devfile.io/devworkspace_name=<workspace-name>
        return """
            podName=${'$'}(kubectl get pod -n $namespace -l controller.devfile.io/devworkspace_name=$workspaceName --no-headers -o custom-columns=":metadata.name" | head -n 1)
            echo "Connecting to workspace '$workspaceName' in namespace '$namespace'..."
            echo "Pod: ${'$'}podName"
            echo "Executing: kubectl exec -it -n $namespace ${'$'}podName -- bash"
            echo ""
            kubectl exec -it -n $namespace ${'$'}podName -- bash
        """.trimIndent()
    }

    private fun openMacTerminal(namespace: String, workspaceName: String) {
        logger.debug("Opening macOS Terminal for workspace: $workspaceName in namespace: $namespace")

        val shellScript = buildShellScript(namespace, workspaceName)
        logger.debug("Shell command: $shellScript")

        // Create a .command file - macOS will open it with the user's default terminal app
        val commandFile = createTempScriptFile(".command", "#!/bin/bash\n$shellScript\nexec bash\n")
        logger.debug("Created .command file: ${commandFile.absolutePath}")

        // Use 'open' to open the .command file with the user's default terminal
        executeProcess("open", commandFile.absolutePath)
        logger.debug("Successfully opened terminal with user's default terminal app")
    }

    private fun openWindowsTerminal(namespace: String, workspaceName: String) {
        logger.debug("Opening Windows terminal for workspace: $workspaceName in namespace: $namespace")

        val batchScript = """
            @echo off
            echo Connecting to workspace '$workspaceName' in namespace '$namespace'...
            for /f %%i in ('kubectl get pod -n $namespace -l controller.devfile.io/devworkspace_name^=$workspaceName --no-headers -o custom-columns^=":metadata.name"') do (
                echo Pod: %%i
                echo Executing: kubectl exec -it -n $namespace %%i -- bash
                echo.
                kubectl exec -it -n $namespace %%i -- bash
                goto :done
            )
            :done
        """.trimIndent()

        val tempFile = createTempScriptFile(".bat", batchScript, executable = false)
        logger.debug("Created temporary batch file: ${tempFile.absolutePath}")

        executeProcess("cmd", "/c", "start", "cmd", "/k", tempFile.absolutePath)
        logger.debug("Launched cmd.exe with batch script")
    }

    private fun openLinuxTerminal(namespace: String, workspaceName: String) {
        logger.debug("Opening Linux terminal for workspace: $workspaceName in namespace: $namespace")

        val shellScript = buildShellScript(namespace, workspaceName)
        val fullCommand = "$shellScript; exec bash"

        val terminalCommands = listOf(
            listOf("gnome-terminal", "--", "bash", "-c", fullCommand),
            listOf("konsole", "-e", "bash", "-c", fullCommand),
            listOf("xterm", "-e", "bash", "-c", fullCommand),
            listOf("x-terminal-emulator", "-e", "bash", "-c", fullCommand)
        )

        tryLaunchTerminal(terminalCommands)
    }

    private fun tryLaunchTerminal(terminalCommands: List<List<String>>) {
        var lastException: Exception? = null
        for (command in terminalCommands) {
            val terminalName = command.firstOrNull() ?: "unknown"
            try {
                logger.debug("Attempting to launch terminal: $terminalName")
                executeProcess(*command.toTypedArray())
                logger.debug("Successfully launched terminal: $terminalName")
                return
            } catch (e: Exception) {
                logger.debug("Failed to launch $terminalName: ${e.message}")
                lastException = e
            }
        }
        logger.error("No suitable terminal emulator found on Linux. Tried: ${terminalCommands.mapNotNull { it.firstOrNull() }}")
        throw lastException ?: RuntimeException("No suitable terminal emulator found on Linux")
    }

    private fun createTempScriptFile(extension: String, content: String, executable: Boolean = true): java.io.File {
        return createTempFile("devspaces-terminal", extension).apply {
            writeText(content)
            if (executable) setExecutable(true)
            deleteOnExit()
        }
    }

    private fun executeProcess(vararg command: String) {
        logger.trace("Starting process: ${command.joinToString(" ")}")
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            logger.error("Process failed with exit code $exitCode. Command: ${command.joinToString(" ")}")
            logger.error("Process output: $output")
            throw RuntimeException("Failed to start terminal. Exit code: $exitCode, Output: $output")
        }

        if (output.isNotBlank()) {
            logger.debug("Process output: $output")
        }
    }
}
