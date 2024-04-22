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
package com.redhat.devtools.gateway.openshift

import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.nio.*
import io.kubernetes.client.Exec
import io.kubernetes.client.PortForward
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.util.Streams
import kotlinx.coroutines.*
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.*


class Pods(private val client: ApiClient) {

    // Example:
    // https://github.com/kubernetes-client/java/blob/master/examples/examples-release-18/src/main/java/io/kubernetes/client/examples/ExecExample.java
    @Throws(IOException::class)
    fun exec(pod: V1Pod, command: Array<String>, container: String): String {
        val output = ByteArrayOutputStream()

        val process = Exec(client).exec(pod, command, container, false, false)
        val copyOutThread =
            Thread {
                Streams.copy(process.inputStream, output)
            }
        copyOutThread.start()

        process.waitFor()
        copyOutThread.join()
        process.destroy()

        return output.toString()
    }

    // Example:
    // https://github.com/kubernetes-client/java/blob/master/examples/examples-release-18/src/main/java/io/kubernetes/client/examples/PortForwardExample.java
    @Throws(IOException::class)
    fun forward(pod: V1Pod, localPort: Int, remotePort: Int): Closeable {
        val serverSocket = ServerSocket(localPort, 50, InetAddress.getLoopbackAddress())

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            supervisorScope {
                launch {
                    val clientSocket = serverSocket.accept()
                    val forwardResult = PortForward(client).forward(pod, listOf(remotePort))

                    try {
                        copyStreams(clientSocket, forwardResult, remotePort)
                    } catch (e: IOException) {
                        if (coroutineContext.isActive) throw e
                    } finally {
                        clientSocket.close()
                    }
                }
            }
        }

        return Closeable {
            scope.cancel()
            runBlocking {
                scope.coroutineContext.job.join()
            }

            serverSocket.close()
        }
    }

    @Throws(IOException::class)
    private suspend fun copyStreams(
        clientSocket: Socket,
        forwardResult: PortForward.PortForwardResult,
        remotePort: Int
    ) {
        coroutineScope {
            ensureActive()
            launch {
                copyStreams(forwardResult.getInputStream(remotePort), clientSocket.getOutputStream())
            }
            launch {
                copyStreams(clientSocket.getInputStream(), forwardResult.getOutboundStream(remotePort))
            }
        }
    }

    @Throws(IOException::class)
    private suspend fun copyStreams(source: InputStream, destination: OutputStream) {
        val buffer = ByteArray(Streams.BUFFER_SIZE)

        var bytesRead = 0
        while (runInterruptible { source.read(buffer).also { bytesRead = it } } >= 0) {
            destination.run { write(buffer, 0, bytesRead) }
        }
        destination.run { flush() }
    }

    @Throws(ApiException::class)
    fun findFirst(namespace: String, labelSelector: String): V1Pod? {
        val pods = doList(namespace, labelSelector)
        return pods.items[0]
    }

    @Throws(ApiException::class)
    private fun doList(namespace: String, labelSelector: String = ""): V1PodList {
        return CoreV1Api(client).listNamespacedPod(
            namespace,
            "false",
            false,
            "",
            "",
            labelSelector,
            -1,
            "",
            "",
            -1,
            false
        )
    }
}