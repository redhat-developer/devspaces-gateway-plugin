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
package com.github.devspaces.gateway.openshift

import io.kubernetes.client.PortForward
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.util.Streams
import java.net.ServerSocket


// Sample:
// https://github.com/kubernetes-client/java/blob/master/examples/examples-release-19/src/main/java/io/kubernetes/client/examples/PortForwardExample.java
class DevSpacesPortForward(private val client: ApiClient) {
    private val port = 5990
    private lateinit var toLocalFromRemoteThread: Thread
    private lateinit var fromLocalToRemoteThread: Thread
    fun start(pod: V1Pod) {
        Configuration.setDefaultApiClient(client)
        val forwarding = PortForward().forward(
            pod.metadata?.namespace,
            pod.metadata?.name,
            arrayListOf(port)
        )

        val serverSocket = ServerSocket(port)
        val socket = serverSocket.accept()

        toLocalFromRemoteThread = Thread {
            Streams.copy(forwarding.getInputStream(port), socket.getOutputStream())
        }

        fromLocalToRemoteThread = Thread {
            Streams.copy(socket.getInputStream(), forwarding.getOutboundStream(port))
        }

        toLocalFromRemoteThread.start()
        fromLocalToRemoteThread.start()
    }

    fun stop() {
        toLocalFromRemoteThread.interrupt()
        fromLocalToRemoteThread.interrupt()
    }
}