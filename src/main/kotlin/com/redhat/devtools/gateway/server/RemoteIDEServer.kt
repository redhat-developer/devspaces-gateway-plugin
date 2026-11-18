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
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
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
 * Represent an IDE server running in a CDE.
 *
 * @param devSpacesContext the context that supplies the client and the current workspace.
 * @param workspace the workspace whose IDE server is probed; the context's current workspace by
 *   default. Restart passes the workspace it captured, so a context change cannot redirect the
 *   readiness probe.
 */
class RemoteIDEServer(
    private val devSpacesContext: DevSpacesContext,
    private val workspace: DevWorkspace = devSpacesContext.devWorkspace
) {
    /**
     * The pod that serves the workspace and the IDE container inside it, as one unit.
     *
     * A pod roll replaces both together: a container refreshed on its own could survive onto a new
     * pod and make an exec land in the previous pod's container.
     */
    private data class Target(val pod: V1Pod, val container: V1Container)

    /** Guards the swap in [refreshPod]; readers see a whole [Target] through the volatile field. */
    private val targetLock = Any()

    @Volatile
    private var target: Target = resolveTarget()

    /** The pod that currently serves the workspace. Retargeting swaps it together with its container. */
    val pod: V1Pod
        get() = target.pod

    /**
     * Name of the IDE container inside [pod]. Exposed to prove the pod/container pairing: a
     * container name from the previous pod must never be used with a new pod.
     */
    internal val ideContainerName: String
        get() = target.container.name

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

    /**
     * Asks the CDE for the remote IDE server status.
     */
    @Throws(CancellationException::class)
    suspend fun getStatus(checkCancelled: (() -> Unit)? = null): RemoteIDEServerStatus =
        withContext(Dispatchers.IO) {
            checkCancelled?.invoke()
            // one snapshot: the pod and the container must belong to the same generation
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

    private suspend fun waitForState(
        isReadyState: Boolean,
        timeout: Long,
        checkCancelled: (() -> Unit)? = null
    ): Boolean {
        return RemoteIDEServerReadiness(
            targetDescription = {
                val current = target
                "IDE server on pod '${current.pod.metadata?.name}' " +
                    "container '${current.container.name}'"
            },
            isReady = { cancelled -> getStatus(cancelled).isReady },
            refresh = { refreshPod() },
        ).waitFor(isReadyState, timeout, checkCancelled)
    }

    /**
     * Re-resolves the workspace's current running and ready pod and swaps it in together with its
     * IDE container, so a later status exec can never land in the previous pod's container.
     *
     * @return whether a pod was found. When the workspace has no running, ready pod right now the
     * previous target is kept, and the next readiness probe reports it as not running.
     * @throws ServerContainerNotFoundException if the running pod has no idea-server container:
     * that is terminal, not a transient state to poll through (CRW-11897).
     */
    @Throws(ServerContainerNotFoundException::class)
    fun refreshPod(): Boolean {
        val refreshed = resolveTargetOrNull() ?: run {
            thisLogger().info(
                "No running, ready pod for DevWorkspace '${workspace.name}'; keeping the current target."
            )
            return false
        }
        synchronized(targetLock) { target = refreshed }
        thisLogger().info(
            "IDE server target refreshed: pod '${refreshed.pod.metadata?.name}' " +
                "container '${refreshed.container.name}'."
        )
        return true
    }

    /**
     * Resolves the pod that currently serves the workspace.
     *
     * Deliberately selects a running, ready pod instead of the first list item: during a pod roll
     * the first item can be the terminating pod, which would pin every status exec and
     * port-forward to a pod that no longer serves. While the pod is running but not yet ready, the
     * constructor throws; [RemoteIDEServerReadiness] and the caller's retry loop re-resolve the pod
     * on the next poll.
     *
     * @throws IOException if no pod of the workspace is running and ready.
     */
    @Throws(IOException::class)
    private fun findPod(): V1Pod {
        val selector = "controller.devfile.io/devworkspace_name=${workspace.name}"

        return DevWorkspacePods(devSpacesContext.client)
            .findFirstRunning(
                workspace.namespace,
                selector
            ) ?: throw IOException(
            "DevWorkspace '${workspace.name}' is not running.",
        )
    }

    @Throws(IOException::class)
    private fun resolveTarget(): Target {
        val pod = findPod()
        return Target(pod, findIdeContainer(pod))
    }

    /** Like [resolveTarget], but `null` when the workspace has no running, ready pod. */
    private fun resolveTargetOrNull(): Target? {
        val pod = try {
            findPod()
        } catch (e: IOException) {
            thisLogger().debug("Pod lookup failed while refreshing the IDE server target.", e)
            return null
        }
        return Target(pod, findIdeContainer(pod))
    }

    @Throws(ServerContainerNotFoundException::class)
    private fun findIdeContainer(pod: V1Pod): V1Container {
        return pod.spec!!.containers.find { container ->
            // a container without the idea-server port is not the IDE container; `orEmpty` keeps
            // a container whose ports are unset from matching on "has a ports list"
            container.ports.orEmpty().any { port -> port.name == "idea-server" }
        }
            ?: throw ServerContainerNotFoundException(
                "Workspace IDE container not found in the Pod: ${pod.metadata?.name}"
            )
    }
}
