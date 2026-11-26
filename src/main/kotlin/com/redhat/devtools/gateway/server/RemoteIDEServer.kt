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
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.openshift.DevWorkspacePods
import com.redhat.devtools.gateway.openshift.PodExecSession
import io.kubernetes.client.openapi.ApiClient
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

        /**
         * Status command run in the IDE container. `remote-dev-server.sh` writes to several
         * sub-folders of HOME (`.config`, `.cache`, ...), and a container on
         * registry.access.redhat.com/ubi9 has a read-only `HOME=/`, so the command points it at a
         * writable HOME.
         */
        private val STATUS_COMMAND = arrayOf(
            "env",
            "HOME=/tmp/user",
            "/bin/sh",
            "-c",
            "/idea-server/bin/remote-dev-server.sh status \$PROJECT_SOURCE | awk '/STATUS:/{p=1; next} p'"
        )
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
            if (!isPodRunning(devSpacesContext.client, current.pod)) {
                return@withContext RemoteIDEServerStatus.empty()
            }
            checkCancelled?.invoke()
            val output = executeStatus(current, checkCancelled)
            checkCancelled?.invoke()
            parseStatus(output)
        }

    /** Runs the status command in the IDE container and returns its raw output. */
    private suspend fun executeStatus(current: Target, checkCancelled: (() -> Unit)?): String =
        PodExecSession(
            client = devSpacesContext.client,
            pod = current.pod,
            container = current.container.name,
            command = STATUS_COMMAND,
            timeout = STATUS_EXEC_TIMEOUT,
            checkCancelled = checkCancelled
        ).execute().trim()

    /** Parses the status command's output; anything unparseable reads as an empty (not ready) status. */
    private fun parseStatus(output: String): RemoteIDEServerStatus {
        if (output.isEmpty()) return RemoteIDEServerStatus.empty()
        return runCatching { Gson().fromJson(output, RemoteIDEServerStatus::class.java) }
            .onFailure { e ->
                thisLogger().warn(
                    "Failed to parse remote IDE server status output, " +
                        "treating as not ready. Output: ${output.take(500)}",
                    e,
                )
            }
            .getOrNull()
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
     * Deliberately selects a running pod instead of the first list item: during a pod roll the first
     * item can be the terminating pod, which would pin every status exec and port-forward to a pod
     * that no longer serves. Readiness is *not* required: a pod that has not passed its probe yet is
     * still the workspace's pod, and failing here would break a connect that only needs to wait.
     * [RemoteIDEServerReadiness] retries the resulting not-ready status, [refreshPod] retargets once
     * the pod is ready (CRW-13910).
     *
     * @throws IOException if no pod of the workspace is running.
     */
    @Throws(IOException::class)
    private fun findPod(): V1Pod {
        return DevWorkspacePods(devSpacesContext.client)
            .findServingPod(workspace.namespace, workspace.name, requireReady = false)
            ?: throw IOException(
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

}

/**
 * Whether [pod] still serves the workspace. A pod that no longer runs yields an empty status rather
 * than an exec against a dead container.
 */
private fun isPodRunning(client: ApiClient, pod: V1Pod): Boolean {
    if (DevWorkspacePods(client).isPodRunning(pod)) return true
    logger<RemoteIDEServer>().info("Pod '${pod.metadata?.name}' is not running; returning empty status.")
    return false
}

/** The idea-server container of [pod]; a pod without one cannot serve the IDE. */
@Throws(ServerContainerNotFoundException::class)
private fun findIdeContainer(pod: V1Pod): V1Container =
    pod.spec!!.containers.find { container ->
        // a container without the idea-server port is not the IDE container; `orEmpty` keeps
        // a container whose ports are unset from matching on "has a ports list"
        container.ports.orEmpty().any { port -> port.name == "idea-server" }
    }
        ?: throw ServerContainerNotFoundException(
            "Workspace IDE container not found in the Pod: ${pod.metadata?.name}"
        )
