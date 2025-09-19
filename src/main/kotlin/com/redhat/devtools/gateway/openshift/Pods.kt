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

import io.kubernetes.client.Exec
import io.kubernetes.client.PortForward
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

class Pods(private val client: ApiClient) {

    // Example:
    // https://github.com/kubernetes-client/java/blob/master/examples/examples-release-latest/src/main/java/io/kubernetes/client/examples/ExecExample.java
    @Throws(IOException::class)
    fun exec(pod: V1Pod, command: Array<String>, container: String, timeout: Long): String {
        return runBlocking {
            val output = ByteArrayOutputStream()
            val errorOutput = ByteArrayOutputStream()
            val process: Process = try {
                Exec(client).exec(pod, command, container, false, false)
            } catch (e: ApiException) {
                throw IOException("Failed to execute command in pod: ${e.code} - ${e.responseBody}", e)
            } catch (e: IOException) {
                throw IOException("Failed to execute command in pod: ${e.message}", e)
            }

            var stdoutJob: Job? = null
            var stderrJob: Job? = null
            try {
                stdoutJob = copyStream(process.inputStream, output)
                stderrJob = copyStream(process.errorStream, errorOutput)
                val exitCode = waitForProcess(process, timeout)
                stdoutJob.join()
                stderrJob.join()
                if (exitCode != 0) {
                    throw IOException(
                        "Pod.exec() exited with code $exitCode.\nStderr: ${errorOutput.toString(Charsets.UTF_8)}"
                    )
                }
                output.toString(Charsets.UTF_8)
            } finally {
                stdoutJob?.cancelAndJoin()
                stderrJob?.cancelAndJoin()
                process.destroy()
            }
        }
    }

    private suspend fun waitForProcess(process: Process, timeout: Long): Int = withContext(Dispatchers.IO) {
        val exited = process.waitFor(timeout, TimeUnit.SECONDS) // Wait up to 30 seconds
        if (!exited) {
            process.destroyForcibly()
            throw RuntimeException("Command in pod timed out after 30 seconds.")
        }
        process.exitValue()
    }

    private fun CoroutineScope.copyStream(input: InputStream, output: ByteArrayOutputStream
        ): Job = launch(Dispatchers.IO) {
            input.copyTo(output)
        }

    // Example:
    // https://github.com/kubernetes-client/java/blob/master/examples/examples-release-latest/src/main/java/io/kubernetes/client/examples/PortForwardExample.java
    @Throws(IOException::class)
    fun forward(pod: V1Pod, localPort: Int, remotePort: Int): Closeable {
        val portForward = PortForward(client)
        val forwardResult = portForward.forward(pod, listOf(remotePort))
        val serverSocket = ServerSocket(localPort, 50, InetAddress.getLoopbackAddress())

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val clientSocket = serverSocket.accept()
            try {
                copyStreams(clientSocket, forwardResult, remotePort)
            } catch (e: IOException) {
                if (coroutineContext.isActive) throw e
            } finally {
                clientSocket.close()
            }
        }

        return Closeable {
            try {
                scope.cancel()
                runBlocking {
                    scope.coroutineContext.job.join()
                }

                serverSocket.close()
                try {
                    forwardResult.getInputStream(remotePort).close()
                    forwardResult.getOutboundStream(remotePort).close()
                } catch (_: Exception) {
                    // Ignore errors when closing streams
                }
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
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
    private fun copyStreams(source: InputStream, destination: OutputStream) {
        source.copyTo(destination)
        destination.run { flush() }
    }

    @Throws(IOException::class)
    fun waitForForwardReady(port: Int) {
        val maxRetries = 30
        val retryDelay = 100L

        repeat(maxRetries) { attempt ->
            try {
                val testSocket = ServerSocket()
                testSocket.bind(java.net.InetSocketAddress("127.0.0.1", port))
                testSocket.close()
                // If we can bind to the port, it means port forwarding is not ready yet
                Thread.sleep(retryDelay)
            } catch (_: java.net.BindException) {
                // Port is already in use, which means port forwarding is ready
                return
            } catch (e: Exception) {
                if (attempt == maxRetries - 1) {
                    throw IOException("Port forwarding to local port $port is not ready after ${maxRetries * retryDelay}ms", e)
                }
                Thread.sleep(retryDelay)
            }
        }

        throw IOException("Port forwarding to local port $port is not ready after ${maxRetries * retryDelay}ms")
    }

    @Throws(ApiException::class)
    fun findFirst(namespace: String, labelSelector: String): V1Pod? {
        val pods = doList(namespace, labelSelector)
        return pods.items[0]
    }

    @Throws(ApiException::class)
    private fun doList(namespace: String, labelSelector: String = ""): V1PodList {
        return CoreV1Api(client)
            .listNamespacedPod(namespace)
            .labelSelector(labelSelector)
            .execute();
    }
}