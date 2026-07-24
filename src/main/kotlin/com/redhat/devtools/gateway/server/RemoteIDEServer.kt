/*
 * Copyright (c) 2024-2026 Red Hat, Inc.
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
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
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
        /** Overall wait for remote IDE readiness (wizard "Checking workspace IDE Status..."). */
        var readyTimeout: Long = 120 // seconds

        /**
         * Per-probe exec budget while polling. Must stay well below [readyTimeout] so a slow/hung
         * status exec cannot burn the entire wait (CRW-11119).
         */
        const val STATUS_EXEC_TIMEOUT: Long = 15 // seconds

        /** Number of consecutive pod-refresh failures before emitting a warning. */
        const val REFRESH_FAILURE_WARNING_THRESHOLD: Int = 10
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
            if (!DevWorkspacePods(devSpacesContext.client).isPodRunning(pod)) {
                return@withContext RemoteIDEServerStatus.empty()
            }
            checkCancelled?.invoke()
            val output = DevWorkspacePods(devSpacesContext.client).exec(
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
                timeout = STATUS_EXEC_TIMEOUT
            ).trim()

            checkCancelled?.invoke()
            output.takeIf { it.isNotEmpty() }?.let {
                runCatching { Gson().fromJson(it, RemoteIDEServerStatus::class.java) }.getOrNull()
            } ?: RemoteIDEServerStatus.empty()
        }

    /**
     * Waits for the server to be ready for the given timeout period.
     *
     * @param checkCancelled the check for user cancellation.
     * @param timeout maximum waiting period in seconds
     * @return True if the server is ready within the timeout, False otherwise.
     */
    @Throws(IOException::class)
    suspend fun waitServerReady(checkCancelled: (() -> Unit)? = null, timeout: Long = readyTimeout): Boolean {
        return doWaitServerState(true, timeout, checkCancelled)
            .also {
                if (!it) throw IOException(
                    "Workspace IDE is not ready after $timeout seconds.",
                )
            }
    }

    @Throws(CancellationException::class)
    private suspend fun isServerState(
        isReadyState: Boolean,
        checkCancelled: (() -> Unit)? = null,
        refreshPodBeforeCheck: Boolean = false,
        refreshFailures: IntArray = intArrayOf(0),
    ): Boolean {
        return try {
            if (refreshPodBeforeCheck) {
                runCatching {
                    pod = findPod()
                    container = findContainer()
                }.onFailure { e ->
                    if (e.isCancellationException()) throw e
                    refreshFailures[0]++
                    thisLogger().debug("Failed to refresh workspace pod during IDE state check", e)
                    if (refreshFailures[0] == REFRESH_FAILURE_WARNING_THRESHOLD) {
                        thisLogger().warn(
                            "Pod/container refresh has failed ${refreshFailures[0]} consecutive times; " +
                            "stale pod references may cause incorrect status checks"
                        )
                    }
                    return false
                }.onSuccess { refreshFailures[0] = 0 }
            }
            getStatus(checkCancelled).isReady == isReadyState
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            thisLogger().debug("Failed to check workspace IDE state.", e)
            false
        }
    }

    @Throws(IOException::class)
    suspend fun waitServerTerminated(timeout: Long = 10L): Boolean {
        return doWaitServerState(false, timeout)
    }

    /**
     * Waits for the server to have or not have projects according to the given parameter.
     * Times out the wait if the expected state is not reached within specified timeout.
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
        @Suppress("ConvertLongToDuration")
        withTimeoutOrNull(timeout * 1000L) {
            val refreshFailures = intArrayOf(0)
            while (true) {
                checkCancelled?.invoke()
                if (isServerState(
                        isReadyState,
                        checkCancelled,
                        // Re-resolve pod while waiting for ready so a recycled pod is not missed.
                        refreshPodBeforeCheck = isReadyState,
                        refreshFailures,
                    )
                ) {
                    return@withTimeoutOrNull true
                }

                yield()
                delay(500L)
            }

            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false

    @Throws(IOException::class)
    private fun findPod(): V1Pod {
        val selector = "controller.devfile.io/devworkspace_name=${devSpacesContext.devWorkspace.name}"

        return DevWorkspacePods(devSpacesContext.client)
            .findFirst(
                devSpacesContext.devWorkspace.namespace,
                selector
            ) ?: throw IOException(
            "DevWorkspace '${devSpacesContext.devWorkspace.name}' is not running.",
        )
    }

    @Throws(IOException::class)
    private fun findContainer(): V1Container {
        return pod.spec!!.containers.find { container ->
            container.ports?.any { port -> port.name == "idea-server" } != null
        }
            ?: throw IOException(
                "Workspace IDE container not found in the Pod: ${pod.metadata?.name}"
            )
    }
}
