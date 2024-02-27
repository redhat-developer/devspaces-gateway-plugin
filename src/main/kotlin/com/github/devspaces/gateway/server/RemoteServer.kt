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

package com.github.devspaces.gateway.server

import com.github.devspaces.gateway.DevSpacesContext
import com.github.devspaces.gateway.openshift.Pods
import com.github.devspaces.gateway.openshift.Utils
import com.google.gson.Gson
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
    private var waitingTimeout: Long = 60

    init {
        pod = findPod()
        container = findContainer()
    }

    fun getProjectStatus(): ProjectStatus {
        val result = Pods(devSpacesContext.client).exec(
            pod, arrayOf(
                "/bin/sh",
                "-c",
                "/idea-server/bin/remote-dev-server.sh status \$PROJECT_SOURCE | awk '/STATUS:/{p=1; next} p'"
            ), container.name
        ).trim()

        return Gson().fromJson(result, ProjectStatus::class.java)
    }

    @Throws(IOException::class)
    fun waitProjects() {
        val projectsReady = AtomicBoolean()
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.scheduleAtFixedRate(
            {
                val projectStatus = getProjectStatus()
                if (!Arrays.isNullOrEmpty(projectStatus.projects)) {
                    projectsReady.set(true)
                    executor.shutdown()
                }
            }, 0, 5, TimeUnit.SECONDS
        )

        try {
            executor.awaitTermination(waitingTimeout, TimeUnit.SECONDS)
        } finally {
            executor.shutdown()
        }

        if (!projectsReady.get()) throw IOException(
            String.format(
                "Projects are not ready after %d seconds.",
                waitingTimeout
            )
        )
    }

    @Throws(IOException::class)
    private fun findPod(): V1Pod {
        val name = Utils.getValue(devSpacesContext.devWorkspace, arrayOf("metadata", "name")) as String
        val namespace = Utils.getValue(devSpacesContext.devWorkspace, arrayOf("metadata", "namespace")) as String
        val selector = String.format("controller.devfile.io/devworkspace_name=%s", name)

        return Pods(devSpacesContext.client).findFirst(namespace, selector) ?: throw IOException(
            String.format(
                "DevWorkspace '%s' is not running.", name
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