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

import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.openshift.Pods
import com.google.common.base.Strings
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.thisLogger
import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1Pod
import org.bouncycastle.util.Arrays
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


class RemoteServer(private val devSpacesContext: DevSpacesContext) {
    var pod: V1Pod
    private var container: V1Container
    private var readyTimeout: Long = 60
    private var terminationTimeout: Long = 10

    init {
        pod = findPod()
        container = findContainer()
    }

    fun getProjectStatus(): ProjectStatus {
        Pods(devSpacesContext.client)
            .exec(
                pod,
                arrayOf(
                    "/bin/sh",
                    "-c",
                    "/idea-server/bin/remote-dev-server.sh status \$PROJECT_SOURCE | awk '/STATUS:/{p=1; next} p'"
                ),
                container.name
            )
            .trim()
            .also {
                return if (Strings.isNullOrEmpty(it)) ProjectStatus.empty()
                else Gson().fromJson(it, ProjectStatus::class.java)
            }
    }

    @Throws(IOException::class)
    fun waitProjectsReady() {
        doWaitProjectsState(true, readyTimeout)
            .also {
                if (!it) throw IOException(
                    String.format(
                        "Projects are not ready after %d seconds.",
                        readyTimeout
                    )
                )
            }
    }

    @Throws(IOException::class)
    fun waitProjectsTerminated(): Boolean {
        return doWaitProjectsState(false, terminationTimeout)
    }

    @Throws(IOException::class)
    fun doWaitProjectsState(isReadyState: Boolean, timeout: Long): Boolean {
        val projectsInDesiredState = AtomicBoolean()
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.scheduleAtFixedRate(
            {
                try {
                    getProjectStatus().also {
                        if (isReadyState == !Arrays.isNullOrEmpty(it.projects)) {
                            projectsInDesiredState.set(true)
                            executor.shutdown()
                        }
                    }
                } catch (e: Exception) {
                    thisLogger().debug("Failed to check project status", e)
                }
            }, 0, 5, TimeUnit.SECONDS
        )

        try {
            executor.awaitTermination(timeout, TimeUnit.SECONDS)
        } finally {
            executor.shutdown()
        }

        return projectsInDesiredState.get()
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