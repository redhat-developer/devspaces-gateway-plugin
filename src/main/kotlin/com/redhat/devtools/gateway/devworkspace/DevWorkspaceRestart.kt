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
package com.redhat.devtools.gateway.devworkspace

import com.intellij.openapi.diagnostic.thisLogger
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import io.kubernetes.client.openapi.ApiClient
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Handles the restart of a DevWorkspace when triggered by the restart annotation.
 *
 * The restart process:
 * 1. Close the thin client connection
 * 2. Stop the workspace (spec.started = false)
 * 3. Wait for all pods to be deleted
 * 4. Start the workspace (spec.started = true)
 * 5. Clean up the restart annotation
 */
class DevWorkspaceRestart(
    private val namespace: String,
    private val workspaceName: String,
    private val client: ApiClient,
    private val workspaces: DevWorkspaces = DevWorkspaces(client),
    private val pods: DevWorkspacePods = DevWorkspacePods(client)
) {

    @Suppress("UnstableApiUsage")
    suspend fun execute(thinClient: ThinClientHandle) {
        try {
            close(thinClient)
            stopWorkspace()
            waitForPodsDeleted()
            startWorkspace()
            removeAnnotation()
        } catch (e: Exception) {
            thisLogger().error("Workspace restart failed for $namespace/$workspaceName", e)
            removeAnnotation()
            throw e
        }
    }

    @Suppress("UnstableApiUsage")
    private suspend fun close(thinClient: ThinClientHandle) {
        thisLogger().debug("Closing thin client for $namespace/$workspaceName")
        thinClient.close()
        delay(1.seconds) // Give time for port forwarder cleanup
    }

    private fun stopWorkspace() {
        workspaces.stop(namespace, workspaceName)
        thisLogger().debug("workspace $namespace/$workspaceName stop requested.")
    }

    private suspend fun waitForPodsDeleted() {
        val podsDeleted = pods.waitForPodsDeleted(
            namespace,
            workspaceName,
            20
        )
        if (podsDeleted) {
            thisLogger().debug("All pods for $namespace/$workspaceName have been deleted.")
        } else {
            thisLogger().warn("Pods for $namespace/$workspaceName were not deleted within timeout, proceeding anyway.")
        }
    }

    private fun startWorkspace() {
        workspaces.start(namespace, workspaceName)
        thisLogger().debug("workspace $namespace/$workspaceName start requested.")
    }

    private fun removeAnnotation() {
        runCatching {
            if (workspaces.isRestarting(namespace, workspaceName)) {
                workspaces.removeRestarting(namespace, workspaceName)
                thisLogger().debug("Removed restart annotation from $namespace/$workspaceName")
            } else {
                thisLogger().debug("Restart annotation already removed from $namespace/$workspaceName")
            }
        }.onFailure { e ->
            thisLogger().debug("Failed to remove restart annotation from $namespace/$workspaceName", e)
        }
    }
}
