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

package com.redhat.devtools.gateway.server

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.openshift.Pods
import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1Pod
import org.bouncycastle.util.Arrays
import java.io.IOException

/**
 * Represent an IDE server running in a CDE.
 */
class RemoteIDEServer(private val devSpacesContext: DevSpacesContext) {
    var pod: V1Pod
    private var container: V1Container
    private var readyTimeout: Long = 180 // 3 minutes to allow for IDE restart

    init {
        pod = findPod()
        container = findContainer()
    }

    /**
     * Asks the CDE for the remote IDE server status.
     */
    fun getStatus(): RemoteIDEServerStatus {
        Pods(devSpacesContext.client)
            .exec(
                pod,
                arrayOf(
//                  remote-dev-server.sh writes to several sub-folders of HOME (.config, .cache, etc.)
//                  When registry.access.redhat.com/ubi9 is used for running a user container,
//                  HOME=/ which is read-only.
//                  In this case, we point remote-dev-server.sh to a writable HOME.
                    "env",
                    "HOME=/tmp/user",
                    "/bin/sh",
                    "-c",
                    "/idea-server/bin/remote-dev-server.sh status \$PROJECT_SOURCE | awk '/STATUS:/{p=1; next} p'"
                ),
                container.name,
                readyTimeout
            )
            .trim()
            .also { status ->
                logger<RemoteIDEServer>().debug("remote server status: $status")
                return if (status.isEmpty()) {
                    RemoteIDEServerStatus.empty()
                } else {
                    Gson().fromJson(status, RemoteIDEServerStatus::class.java)
                }
            }
    }

    @Throws(IOException::class)
    fun waitServerReady(timeoutSeconds: Long = 30) {
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000 // Convert seconds to milliseconds
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (doWaitServerState(true)) {
                thisLogger().debug("Remote IDE server is ready")
                return
            }
            
            try {
                Thread.sleep(5000) // Wait 5 seconds before next check
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for server to be ready")
            }
        }
        
        throw IOException(
            "Remote IDE server is not ready after $timeoutSeconds seconds."
        )
    }

    @Throws(IOException::class)
    fun waitServerTerminated(): Boolean {
        return doWaitServerState(false)
    }

    @Throws(IOException::class)
    fun doWaitServerState(isReadyState: Boolean): Boolean {
        return try {
            val status = getStatus()
            isReadyState == !Arrays.isNullOrEmpty(status.projects)
        } catch (e: Exception) {
            thisLogger().debug("Failed to check remote IDE server state.", e)
            false
        }
    }

    @Throws(IOException::class)
    private fun findPod(): V1Pod {
        val selector = "controller.devfile.io/devworkspace_name=${devSpacesContext.devWorkspace.name}"

        return Pods(devSpacesContext.client)
            .findFirst(
                devSpacesContext.devWorkspace.namespace,
                selector
            ) ?: throw IOException(
            "DevWorkspace '${devSpacesContext.devWorkspace.name}' is not running.",
        )
    }

    @Throws(IOException::class)
    private fun findContainer(): V1Container {
        return pod.spec!!.containers.find { container -> container.ports?.any { port -> port.name == "idea-server" } != null }
            ?: throw IOException(
                "Remote server container not found in the Pod: ${pod.metadata?.name}"
            )
    }
}
