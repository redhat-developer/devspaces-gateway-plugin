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
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import com.redhat.devtools.gateway.util.isCancellationException
import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Represent an IDE server running in a CDE.
 */
class RemoteIDEServer(private val devSpacesContext: DevSpacesContext) {

    private var cachedPod: V1Pod? = null

    companion object {
        const val READY_TIMEOUT: Long = 120 // seconds
        /** Per-probe exec budget while polling; must be well below [READY_TIMEOUT]. */
        const val STATUS_EXEC_TIMEOUT: Long = 15 // seconds
        val DRAIN_DELAY: kotlin.time.Duration = 500.milliseconds
    }

    /**
     * Returns the cached workspace pod, or fetches it from the cluster if not yet cached.
     */
    @Throws(IOException::class)
    fun getPod(): V1Pod {
        cachedPod?.let { return it }
        return refreshPod()
    }

    /**
     * Sets the given pod.
     *
     * @see [getPod]
     * @see [refreshPod]
     */
    fun setPod(pod: V1Pod) {
        cachedPod = pod
    }

    /**
     * Re-queries the cluster for the workspace pod and updates the cache.
     */
    @Throws(IOException::class)
    fun refreshPod(): V1Pod = fetchPod().also { cachedPod = it }

    /**
     * Returns the IDE container from the workspace pod.
     */
    @Throws(IOException::class)
    fun getContainer(): V1Container = findContainer(getPod())

    /**
     * Fetches the workspace pod and IDE container, then asks the CDE for the remote IDE server status.
     *
     * @param execTimeoutSeconds max seconds to wait for a single status exec probe
     */
    @Throws(CancellationException::class, IOException::class)
    suspend fun fetchStatus(
        checkCancelled: (() -> Unit)? = null,
        execTimeoutSeconds: Long = STATUS_EXEC_TIMEOUT,
    ): RemoteIDEServerStatus =
        withContext(Dispatchers.IO) {
            checkCancelled?.invoke()
            val pod = getPod()
            val container = findContainer(pod)
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
                timeout = execTimeoutSeconds,
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
    suspend fun waitServerReady(checkCancelled: (() -> Unit)? = null, timeout: Long = READY_TIMEOUT): Boolean {
        return doWaitServerState(true, timeout, checkCancelled)
            .also {
                if (!it) throw IOException(
                    "Workspace IDE is not ready after $timeout seconds.",
                )
            }
    }

    /**
     * Waits for the IDE server to become ready and returns its join link.
     *
     * Shared by pod-roll reconnect ([com.redhat.devtools.gateway.ThinClientReconnect]) and
     * annotated restart ([com.redhat.devtools.gateway.devworkspace.DevWorkspaceRestart]).
     *
     * @param pod when non-null, seeds the pod cache before waiting (pod-roll path passes the rolled pod)
     * @param checkCancelled optional progress cancellation check
     * @param timeout maximum wait for IDE readiness in seconds
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun awaitJoinLink(
        pod: V1Pod? = null,
        checkCancelled: (() -> Unit)? = null,
        timeout: Long = READY_TIMEOUT,
    ): String {
        pod?.let { setPod(it) }
        waitServerReady(checkCancelled = checkCancelled, timeout = timeout)
        return fetchStatus(checkCancelled = checkCancelled).joinLink
            ?: throw IOException("no join link")
    }

    @Throws(CancellationException::class)
    private suspend fun isServerState(
        isReadyState: Boolean,
        checkCancelled: (() -> Unit)? = null,
        refreshPodBeforeCheck: Boolean = false,
    ): Boolean {
        return try {
            if (refreshPodBeforeCheck) {
                runCatching { refreshPod() }.onFailure { e ->
                    if (e.isCancellationException()) throw e
                    thisLogger().debug("Failed to refresh workspace pod during IDE state check", e)
                    return false
                }
            }
            fetchStatus(checkCancelled).isReady == isReadyState
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
     * Times out the wait if the expected state is not reached within specified timeout (default is 60 seconds).
     *
     * @param isReadyState True if server up and running with the projects all set are expected, False otherwise,
     * @return True if the expected state is achieved within the timeout, False otherwise.
     */
    @Throws(IOException::class, CancellationException::class)
    private suspend fun doWaitServerState(
        isReadyState: Boolean,
        timeout: Long = READY_TIMEOUT,
        checkCancelled: (() -> Unit)? = null
    ): Boolean =
        withTimeoutOrNull(timeout.seconds) {
            while (true) {
                checkCancelled?.invoke()
                if (isServerState(
                        isReadyState,
                        checkCancelled,
                        refreshPodBeforeCheck = isReadyState,
                    )) {
                    return@withTimeoutOrNull true
                }

                yield()
                delay(DRAIN_DELAY)
            }

            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false

    private fun labelSelector(): String =
        "${DevWorkspacePods.WORKSPACE_LABEL_KEY}=${devSpacesContext.devWorkspace.name}"

    @Throws(IOException::class)
    private fun fetchPod(): V1Pod {
        return DevWorkspacePods(devSpacesContext.client)
            .findFirstRunning(
                devSpacesContext.devWorkspace.namespace,
                labelSelector()
            ) ?: throw IOException(
            "DevWorkspace '${devSpacesContext.devWorkspace.name}' is not running.",
        )
    }

    @Throws(IOException::class)
    private fun findContainer(pod: V1Pod): V1Container {
        return pod.spec!!.containers.find { container ->
            container.ports?.any { port -> port.name == "idea-server" } != null
        }
            ?: throw IOException(
                "Workspace IDE container not found in the Pod: ${pod.metadata?.name}"
            )
    }
}
