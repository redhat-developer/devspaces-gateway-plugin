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
import com.redhat.devtools.gateway.openshift.PodExecSession
import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Thrown when the workspace pod no longer contains an idea-server container.
 * This is a terminal condition — the workspace is unusable — so it must fail fast
 * instead of being retried until the ready timeout elapses (CRW-11897).
 */
class ServerContainerNotFoundException(message: String) : IOException(message)

/**
 * Thrown when the IDE server is ready but reports no join link.
 *
 * The ready → join-link transition can flap right after a pod roll (the status exec races
 * the server startup), so reconnects treat this as transient and retry; only exhausted
 * retries fail the session.
 */
class JoinLinkMissingException(message: String) : IOException(message)

/**
 * Represent an IDE server running in a CDE.
 */
class RemoteIDEServer(private val devSpacesContext: DevSpacesContext) {

    /**
     * Pod + IDE container, swapped atomically: the pod and its container are refreshed from
     * different threads (port-forward resolution, reconnect) and a probe must never target a
     * pod with another pod's container.
     */
    private data class Target(val pod: V1Pod, val container: V1Container)

    @Volatile
    private var target: Target

    /** The workspace pod that status probes currently target. */
    val pod: V1Pod
        get() = target.pod

    companion object {
        /** Overall wait for remote IDE readiness (wizard "Checking workspace IDE Status..."). */
        const val READY_TIMEOUT: Long = 120 // seconds

        /**
         * Per-probe exec budget while polling. Must stay well below [READY_TIMEOUT] so a slow/hung
         * status exec cannot burn the entire wait (CRW-11119).
         */
        const val STATUS_EXEC_TIMEOUT: Long = 15 // seconds

        /** Default wait for the IDE server to disappear before the workspace is stopped. */
        const val TERMINATED_TIMEOUT: Long = 10 // seconds
    }

    init {
        val pod = findPod()
        target = Target(pod, findContainer(pod))
    }

    /**
     * Asks the CDE for the remote IDE server status.
     */
    @Throws(CancellationException::class)
    suspend fun getStatus(checkCancelled: (() -> Unit)? = null): RemoteIDEServerStatus =
        withContext(Dispatchers.IO) {
            checkCancelled?.invoke()
            val current = target
            if (!DevWorkspacePods(devSpacesContext.client).isPodRunning(current.pod)) {
                thisLogger().info(
                    "Pod '${current.pod.metadata?.name}' is not running; " +
                        "returning empty status."
                )
                return@withContext RemoteIDEServerStatus.empty()
            }
            checkCancelled?.invoke()
            val output = PodExecSession(
                client = devSpacesContext.client,
                pod = current.pod,
                container = current.container.name,
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
                timeout = STATUS_EXEC_TIMEOUT,
                checkCancelled = checkCancelled
            ).execute().trim()

            checkCancelled?.invoke()
            output.takeIf { it.isNotEmpty() }
                ?.let {
                    runCatching { Gson().fromJson(it, RemoteIDEServerStatus::class.java) }
                        .onFailure { e ->
                            thisLogger().warn(
                                "Failed to parse remote IDE server status output, " +
                                    "treating as not ready. Output: ${it.take(500)}",
                                e,
                            )
                        }
                        .getOrNull()
                }
                ?: RemoteIDEServerStatus.empty()
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
        return waitForState(true, timeout, checkCancelled)
            .also {
                if (!it) throw IOException(
                    "Workspace IDE is not ready after $timeout seconds.",
                )
            }
    }

    @Throws(IOException::class)
    suspend fun waitServerTerminated(timeout: Long = TERMINATED_TIMEOUT): Boolean {
        return waitForState(false, timeout)
    }

    /**
     * Seeds the cached pod, e.g. with the pod observed after a roll, and refreshes the
     * cached IDE container so status probes target the new pod.
     */
    @Throws(IOException::class)
    fun setPod(pod: V1Pod) {
        target = Target(pod, findContainer(pod))
    }

    /**
     * Waits for the IDE server to become ready and returns its join link.
     *
     * @param checkCancelled optional progress cancellation check
     * @param timeout maximum wait for IDE readiness in seconds
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun awaitJoinLink(
        checkCancelled: (() -> Unit)? = null,
        timeout: Long = READY_TIMEOUT,
    ): String {
        waitServerReady(checkCancelled = checkCancelled, timeout = timeout)
        return getStatus(checkCancelled).joinLink
            ?: throw JoinLinkMissingException("IDE server is ready but reports no join link")
    }

    private suspend fun waitForState(
        isReadyState: Boolean,
        timeout: Long,
        checkCancelled: (() -> Unit)? = null
    ): Boolean {
        return RemoteIDEServerReadiness(
            targetDescription = {
                val current = target
                "IDE server on pod '${current.pod.metadata?.name}' container '${current.container.name}'"
            },
            isReady = { cancelled -> getStatus(cancelled).isReady },
            refresh = { refreshPod() },
        ).waitFor(isReadyState, timeout, checkCancelled)
    }

    /** Re-queries the cluster for the current running workspace pod and refreshes the cached container. */
    @Throws(IOException::class)
    fun refreshPod(): V1Pod {
        val pod = findPod()
        target = Target(pod, findContainer(pod))
        return pod
    }

    @Throws(IOException::class)
    private fun findPod(): V1Pod {
        val selector = "controller.devfile.io/devworkspace_name=${devSpacesContext.devWorkspace.name}"

        return DevWorkspacePods(devSpacesContext.client)
            .findFirstRunning(
                devSpacesContext.devWorkspace.namespace,
                selector
            ) ?: throw IOException(
            "DevWorkspace '${devSpacesContext.devWorkspace.name}' is not running.",
        )
    }

    @Throws(IOException::class)
    private fun findContainer(pod: V1Pod): V1Container {
        return pod.spec!!.containers.find { container ->
            container.ports?.any { port -> port.name == "idea-server" } != null
        }
            ?: throw ServerContainerNotFoundException(
                "Workspace IDE container not found in the Pod: ${pod.metadata?.name}"
            )
    }
}
