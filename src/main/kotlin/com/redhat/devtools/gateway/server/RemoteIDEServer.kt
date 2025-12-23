/*
 * Copyright (c) 2024-2025 Red Hat, Inc.
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
import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.openshift.Pods
import com.redhat.devtools.gateway.util.isCancellationException
import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Represent an IDE server running in a CDE.
 */
class RemoteIDEServer(private val devSpacesContext: DevSpacesContext) {
    var pod: V1Pod
    private var container: V1Container

    companion object {
        var readyTimeout: Long = 60
    }

    init {
        pod = findPod()
        container = findContainer()
    }

    /**
     * Asks the CDE for the remote IDE server status.
     */
    @Throws(CancellationException::class)
    suspend fun getStatus(checkCancelled: (() -> Unit)? = null): RemoteIDEServerStatus =
        withContext(Dispatchers.IO) {
            checkCancelled?.invoke()
            val output = Pods(devSpacesContext.client).exec(
                pod = pod,
                container = container.name,
                command = arrayOf(
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
                timeout = readyTimeout
            ).trim()

            checkCancelled?.invoke()
            output.takeIf { it.isNotEmpty() }?.let {
                runCatching { Gson().fromJson(it, RemoteIDEServerStatus::class.java) }.getOrNull()
            } ?: RemoteIDEServerStatus.empty()
        }


    @Throws(IOException::class)
    suspend fun waitServerReady(checkCancelled: (() -> Unit)? = null) {
        doWaitServerState(true, readyTimeout, checkCancelled)
            .also {
                if (!it) throw IOException(
                    "Remote IDE server is not ready after $readyTimeout seconds.",
                )
            }
    }

    @Throws(CancellationException::class)
    suspend fun isServerState(
        isReadyState: Boolean,
        checkCancelled: (() -> Unit)? = null
    ): Boolean {
        return try {
            getStatus(checkCancelled).isReady == isReadyState
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            thisLogger().debug("Failed to check remote IDE server state.", e)
            false
        }
    }

    @Throws(IOException::class)
    suspend fun waitServerTerminated(): Boolean {
        return doWaitServerState(false, 10L)
    }

    /**
     * Waits for the server to have or not have projects according to the given parameter.
     * Times out the wait if the expected state is not reached within specified timeout (default is 60 seconds).
     *
     * @param isReadyState True if server up and running with the projects all set are expected, False otherwise,
     * @return True if the expected state is achieved within the timeout, False otherwise.
     */
    @Throws(IOException::class, CancellationException::class)
    private suspend fun doWaitServerState(
        isReadyState: Boolean,
        timeout: Long = readyTimeout,
        checkCancelled: (() -> Unit)? = null
    ): Boolean =
        withTimeoutOrNull(timeout * 1000) { // seconds → ms
            while (true) {
                checkCancelled?.invoke()
                if (isServerState(isReadyState, checkCancelled)) {
                    return@withTimeoutOrNull true
                }

                yield()
                delay(500)
            }

            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false

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
