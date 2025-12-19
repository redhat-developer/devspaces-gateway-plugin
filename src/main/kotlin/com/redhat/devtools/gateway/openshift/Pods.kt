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
package com.redhat.devtools.gateway.openshift

import com.intellij.openapi.diagnostic.logger
import com.redhat.devtools.gateway.util.isCancellationException
import io.kubernetes.client.PortForward
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.auth.ApiKeyAuth
import io.kubernetes.client.openapi.auth.HttpBasicAuth
import io.kubernetes.client.openapi.auth.HttpBearerAuth
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Pods(private val client: ApiClient) {

    companion object {
        private const val CONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY: Long = 1000
    }

    private val logger = logger<Pods>()

    // Example:
    // https://github.com/kubernetes-client/java/blob/master/examples/examples-release-latest/src/main/java/io/kubernetes/client/examples/ExecExample.java
    suspend fun exec(
        pod: V1Pod,
        command: Array<String>,
        container: String,
        timeout: Long = 60,
        checkCancelled: (() -> Unit)? = null
    ): String = suspendCancellableCoroutine { cont ->
        val metadata = pod.metadata
            ?: throw IllegalArgumentException("Pod metadata is missing")
        val namespace = metadata.namespace
            ?: throw IllegalArgumentException("Pod namespace is missing")
        val podName = metadata.name
            ?: throw IllegalArgumentException("Pod name is missing")

        val closed = CompletableDeferred<Unit>()
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val execClientApi = createIsolatedExecClient(client)
        var stdoutJob: Job? = null
        var stderrJob: Job? = null
        lateinit var stdoutStream: InputStream
        lateinit var stderrStream: InputStream

        try {
            val execHandle = ContainerAwareExec(execClientApi).containerAwareExec(
                namespace = namespace,
                pod = podName,
                container = container,
                command = command,
                onOpen = { io ->
                    stdoutStream = io.stdout
                    stderrStream = io.stderr

                    stdoutJob = scope.launch { readStream(io.stdout, stdout, checkCancelled) }
                    stderrJob = scope.launch { readStream(io.stderr, stderr, checkCancelled) }

                    scope.launch {
                        try {
                            listOfNotNull(stdoutJob, stderrJob).joinAll()
                            closed.await()

                            checkCancelled?.invoke()
                            cont.resume(stdout.toString())
                        } catch (e: Throwable) {
                            if (e.isCancellationException()) cont.cancel(e)
                            else if (cont.isActive) cont.resumeWithException(e)
                        }
                    }
                },
                onClosed = { _, _ ->
                    closed.complete(Unit)
                },
                onError = { err, _ ->
                    closed.complete(Unit)
                    cont.resumeWithException(err)
                },
                timeoutMs = timeout * 1000,
                tty = false
            )

            cont.invokeOnCancellation { cause ->
                try { stdoutStream.close() } catch (_: Throwable) {}
                try { stderrStream.close() } catch (_: Throwable) {}
                try {
                    execHandle.job.cancel(CancellationException("Pods.exec cancellation"))
                    execHandle.future.cancel(true)
                } catch (_: Throwable) {}
                scope.cancel()
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    private fun readStream(
        input: InputStream,
        output: StringBuilder,
        checkCancelled: (() -> Unit)?
    ) {
        while (true) {
            checkCancelled?.invoke()
            val b = input.read()
            if (b == -1) break
            output.append(b.toChar())
        }
    }

    // Helpers to access private maps using reflection
     private fun ApiClient.copy(): ApiClient {
        val originalDispatcher = this.httpClient.dispatcher
        val newDispatcher = okhttp3.Dispatcher().apply {
            maxRequests = originalDispatcher.maxRequests
            maxRequestsPerHost = originalDispatcher.maxRequestsPerHost
        }
        val newPool = okhttp3.ConnectionPool()

        val newHttp = this.httpClient.newBuilder()
            .dispatcher(newDispatcher)
            .connectionPool(newPool)
            .pingInterval(0, java.util.concurrent.TimeUnit.SECONDS) // IMPORTANT for Exec
            .build()

        val clone = ApiClient(newHttp)

        clone.basePath = this.basePath
        clone.setDebugging(this.isDebugging)
        clone.setVerifyingSsl(this.isVerifyingSsl)
        clone.setSslCaCert(this.sslCaCert)
        clone.setKeyManagers(this.keyManagers)
        clone.setReadTimeout(this.readTimeout)
        clone.setConnectTimeout(this.connectTimeout)
        clone.setWriteTimeout(this.writeTimeout)

        this.authentications.forEach { (name, auth) ->
            val target = clone.getAuthentication(name) ?: return@forEach

            when (auth) {
                is ApiKeyAuth -> {
                    if (target is ApiKeyAuth) {
                        target.apiKey = auth.apiKey
                        target.apiKeyPrefix = auth.apiKeyPrefix
                    }
                }

                is HttpBasicAuth -> {
                    if (target is HttpBasicAuth) {
                        target.username = auth.username
                        target.password = auth.password
                    }
                }

                is HttpBearerAuth -> {
                    if (target is HttpBearerAuth) {
                        // No access to private "scheme" – always use correct "Bearer"
                        target.bearerToken = auth.bearerToken
                    }
                }
            }
        }

        defaultHeaders().forEach { (k, v) -> clone.addDefaultHeader(k, v) }
        defaultCookies().forEach { (k, v) -> clone.addDefaultCookie(k, v) }
        return clone
    }

    // Helpers to access headers/cookies via public API
    fun ApiClient.defaultHeaders(): Map<String, String> {
        return try {
            val field = ApiClient::class.java.getDeclaredField("defaultHeaderMap")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            field.get(this) as Map<String, String>
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun ApiClient.defaultCookies(): Map<String, String> {
        return try {
            val field = ApiClient::class.java.getDeclaredField("defaultCookieMap")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            field.get(this) as Map<String, String>
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun createIsolatedExecClient(base: ApiClient): ApiClient {
        val cloned = base.copy()

        val originalDispatcher = base.httpClient.dispatcher
        cloned.httpClient = base.httpClient.newBuilder()
            .connectionPool(okhttp3.ConnectionPool()) // Still need new pool for isolation
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = originalDispatcher.maxRequests
                maxRequestsPerHost = originalDispatcher.maxRequestsPerHost
            })
            .build()
        return cloned
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
                    if (e.isCancellationException()) throw e
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
            if (e.isCancellationException()) throw e
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
