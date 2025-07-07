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
    private var readyTimeout: Long = 60

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
    fun waitServerReady() {
        doWaitServerState(true)
            .also {
                if (!it) throw IOException(
                    String.format(
                        "Remote IDE server is not ready after %d seconds.",
                        readyTimeout
                    )
                )
            }
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
        val selector =
            String.format(
                "controller.devfile.io/devworkspace_name=%s",
                devSpacesContext.devWorkspace.metadata.name
            )

        return Pods(devSpacesContext.client)
            .findFirst(
                devSpacesContext.devWorkspace.metadata.namespace,
                selector
            ) ?: throw IOException(
            String.format(
                "DevWorkspace '%s' is not running.",
                devSpacesContext.devWorkspace.metadata.name
            )
        )
    }

    @Throws(IOException::class)
    private fun findContainer(): V1Container {
        return pod.spec!!.containers.find { container -> container.ports?.any { port -> port.name == "idea-server" } != null }
            ?: throw IOException(
                String.format(
                    "Remote server container not found in the Pod: %s", pod.metadata?.name
                )
            )
    }
}
