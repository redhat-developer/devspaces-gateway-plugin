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

import com.intellij.openapi.diagnostic.logger
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

class Pods(private val client: ApiClient) {

    companion object {
        private const val CONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY: Long = 1000
    }

    private val logger = logger<Pods>()

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

    @Throws(IOException::class)
    fun forward(pod: V1Pod, localPort: Int, remotePort: Int): Closeable {
        val serverSocket = ServerSocket(localPort, 50, InetAddress.getLoopbackAddress())
        val scope = CoroutineScope(
            // dont cancel if child coroutine fails + use blocking I/O scope
            SupervisorJob() + Dispatchers.IO
        )
        scope.acceptConnections(serverSocket, pod, localPort, remotePort)
        return Closeable {
            runCatching { serverSocket.close() }
            scope.cancel()
        }
    }

    private fun CoroutineScope.acceptConnections(
        serverSocket: ServerSocket,
        pod: V1Pod,
        localPort: Int,
        remotePort: Int
    ) {
        launch {
            logger.info("Starting port forward on local port $localPort...")

            while (isActive) {
                val clientSocket = createClientSocket(serverSocket) ?: break

                launch {
                    handleConnection(
                        clientSocket,
                        pod,
                        localPort,
                        remotePort
                    )
                }
            }
        }
    }

    private suspend fun createClientSocket(serverSocket: ServerSocket): Socket? {
        return try {
            withContext(NonCancellable) {
                // block until connection is accepted
                serverSocket.accept()
            }
        } catch (_: Exception) {
            logger.info("Server socket stopped accepting connections.")
            null
        }
    }

    private suspend fun CoroutineScope.handleConnection(
        clientSocket: Socket,
        pod: V1Pod,
        localPort: Int,
        remotePort: Int
    ) {
        try {
            repeat(CONNECT_ATTEMPTS) { attempt ->
                if (!isActive) return@repeat

                var forwardResult: PortForward.PortForwardResult? = null
                try {
                    logger.info("Attempt #${attempt + 1}: Connecting $localPort -> $remotePort...")
                    val portForward = PortForward(client)
                    forwardResult = portForward.forward(pod, listOf(remotePort))
                    logger.info("forward successful: $localPort -> $remotePort...")
                    copyStreams(clientSocket, forwardResult, remotePort)
                    return
                } catch (e: Exception) {
                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    logger.info(
                        "Could not port forward $localPort -> $remotePort: ${e.message}. Retrying in ${RECONNECT_DELAY}ms..."
                    )
                    if (isActive) {
                        delay(RECONNECT_DELAY)
                    }
                } finally {
                  closeStreams(remotePort, forwardResult)
                }
            }
        } catch(e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            logger.warn(
                "Could not port forward to pod ${pod.metadata?.name} using port $localPort -> $remotePort",
                e)
        } finally {
            runCatching { clientSocket.close() }
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
                try {
                    clientSocket.getInputStream().copyToAndFlush(forwardResult.getOutboundStream(remotePort))
                } catch (e: Exception) {
                    closeStreams(remotePort, forwardResult)
                    throw e
                }
            }
            launch {
                try {
                    forwardResult.getInputStream(remotePort).copyToAndFlush(clientSocket.getOutputStream())
                } catch (e: Exception) {
                    closeStreams(remotePort, forwardResult)
                    throw e
                }
            }
        }
    }

    private fun closeStreams(port: Int, forwardResult: PortForward.PortForwardResult?) {
      runCatching { forwardResult?.getInputStream(port)?.close() }
      runCatching { forwardResult?.getOutboundStream(port)?.close() }
    }

    private fun InputStream.copyToAndFlush(destination: OutputStream) {
        try {
            copyTo(destination)
            destination.flush()
        } catch (e: IOException) {
            logger.info("IOException during stream copy.", e)
            throw e
        }
    }

    @Throws(IOException::class)
    fun waitForForwardReady(port: Int) {
        val maxRetries = 30
        val retryDelay = 100L

        repeat(maxRetries) { attempt ->
            try {
                val testSocket = ServerSocket()
                testSocket.bind(InetSocketAddress("127.0.0.1", port))
                testSocket.close()
                // If we can bind to the port, it means port forwarding is not ready yet
                Thread.sleep(retryDelay)
            } catch (_: BindException) {
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
