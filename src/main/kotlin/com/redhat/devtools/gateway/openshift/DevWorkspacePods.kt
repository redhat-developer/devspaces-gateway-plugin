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
import com.redhat.devtools.gateway.util.isTimeoutException
import com.redhat.devtools.gateway.util.podLogIdentity
import io.kubernetes.client.PortForward
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.time.OffsetDateTime
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

/**
 * Result of resolving the workspace pod for port-forwarding.
 *
 * @param pod the pod to forward to, or null while waiting
 * @param retryDelaySeconds delay before the next resolve attempt when [pod] is null
 */
data class PodForwardResolution(
    val pod: V1Pod?,
    val retryDelaySeconds: Long = DevWorkspacePods.DEFAULT_RECONNECT_DELAY_SECONDS,
)

class DevWorkspacePods(private val client: ApiClient) {

    companion object {
        const val WORKSPACE_LABEL_KEY = "controller.devfile.io/devworkspace_name"
        const val DEFAULT_RECONNECT_DELAY_SECONDS: Long = 3
        private const val FORWARD_READY_RETRY_COUNT: Int = 30
        private const val FORWARD_READY_RETRY_DELAY: Long = 100L // milliseconds
        private val POD_DELETION_WAIT_DELAY: kotlin.time.Duration = 4.seconds
    }

    private val logger = logger<DevWorkspacePods>()

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

    private fun createIsolatedExecClient(base: ApiClient): ApiClient =
        ApiClientUtils.cloneForExec(base)

    @Throws(IOException::class)
    fun forward(
        resolvePod: suspend () -> PodForwardResolution,
        localPort: Int,
        remotePort: Int,
        reconnectTimeoutSeconds: Long,
        reconnectDelaySeconds: Long = DEFAULT_RECONNECT_DELAY_SECONDS,
    ): Closeable {
        val serverSocket = ServerSocket(localPort, 50, InetAddress.getLoopbackAddress())
        val scope = CoroutineScope(
            // dont cancel if child coroutine fails + use blocking I/O scope
            SupervisorJob() + Dispatchers.IO
        )
        scope.acceptConnections(
            serverSocket,
            resolvePod,
            localPort,
            remotePort,
            reconnectTimeoutSeconds,
            reconnectDelaySeconds,
        )
        return Closeable {
            runCatching { serverSocket.close() }
            scope.cancel()
        }
    }

    private fun CoroutineScope.acceptConnections(
        serverSocket: ServerSocket,
        resolvePod: suspend () -> PodForwardResolution,
        localPort: Int,
        remotePort: Int,
        reconnectTimeout: Long,
        reconnectDelaySeconds: Long,
    ) {
        launch {
            logger.info("Starting port forward on local port $localPort...")

            while (isActive) {
                val clientSocket = createClientSocket(serverSocket) ?: break

                launch {
                    handleConnection(
                        clientSocket,
                        resolvePod,
                        localPort,
                        remotePort,
                        reconnectTimeout,
                        reconnectDelaySeconds,
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
        resolvePod: suspend () -> PodForwardResolution,
        localPort: Int,
        remotePort: Int,
        reconnectTimeout: Long,
        reconnectDelaySeconds: Long,
    ) {
        var lastPod: V1Pod? = null
        var lastForwardError: String? = null
        try {
            withTimeout(reconnectTimeout.seconds) {
                while (isActive) {
                    if (!clientSocket.isConnected
                        || clientSocket.isClosed) {
                        return@withTimeout
                    }

                    val resolution = resolvePod()
                    val pod = resolution.pod
                    if (pod == null) {
                        val delaySeconds = resolution.retryDelaySeconds
                        logger.warn(
                            "Port forward $localPort -> $remotePort: no workspace pod yet, " +
                                "retrying in ${delaySeconds}s"
                        )
                        delay(delaySeconds.seconds)
                        continue
                    }
                    lastPod = pod

                    var forwardResult: PortForward.PortForwardResult? = null
                    try {
                        logger.info(
                            "Port forward $localPort -> $remotePort: connecting to ${podLogIdentity(pod)}"
                        )
                        val portForward = PortForward(client)
                        forwardResult = portForward.forward(pod, listOf(remotePort))
                        logger.info("Port forward $localPort -> $remotePort: established to ${podLogIdentity(pod)}")
                        copyStreams(clientSocket, forwardResult, remotePort)
                        return@withTimeout
                    } catch (e: Exception) {
                        if (e.isCancellationException()
                            || e.isTimeoutException()) {
                            throw e
                        }
                        lastForwardError = "${e.javaClass.simpleName}: ${e.message}"
                        logger.info(
                            "Port forward $localPort -> $remotePort: connecting to ${podLogIdentity(pod)}"
                        )
                        delay(reconnectDelaySeconds.seconds)
                    } finally {
                        closeStreams(remotePort, forwardResult)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            val podInfo = lastPod?.let { podLogIdentity(it) } ?: "no pod resolved"
            val errorInfo = lastForwardError?.let { ", last error: $it" } ?: ""
            logger.warn(
                "Port forward $localPort -> $remotePort timed out after ${reconnectTimeout}s " +
                    "(last pod: $podInfo$errorInfo)"
            )
        } catch (e: Exception) {
            if (e.isCancellationException()) throw e
            logger.warn(
                "Could not port forward using port $localPort -> $remotePort",
                e
            )
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
        repeat(FORWARD_READY_RETRY_COUNT) { attempt ->
            try {
                val testSocket = ServerSocket()
                testSocket.bind(InetSocketAddress("127.0.0.1", port))
                testSocket.close()
                // If we can bind to the port, it means port forwarding is not ready yet
                Thread.sleep(FORWARD_READY_RETRY_DELAY)
            } catch (_: BindException) {
                // Port is already in use, which means port forwarding is ready
                return
            } catch (e: Exception) {
                if (attempt == FORWARD_READY_RETRY_COUNT - 1) {
                    throw IOException("Port forwarding to local port $port is not ready after ${FORWARD_READY_RETRY_COUNT * FORWARD_READY_RETRY_DELAY}ms", e)
                }
                Thread.sleep(FORWARD_READY_RETRY_DELAY)
            }
        }

        throw IOException("Port forwarding to local port $port is not ready after ${FORWARD_READY_RETRY_COUNT * FORWARD_READY_RETRY_DELAY}ms")
    }

    @Throws(ApiException::class)
    fun findFirstRunning(namespace: String, labelSelector: String): V1Pod? {
        val pods = list(namespace, labelSelector)
        return pods.items
            .filter { isRunningAndReady(it) }
            .maxByOrNull { it.metadata?.creationTimestamp ?: OffsetDateTime.MIN }
    }

    private fun isRunningAndReady(pod: V1Pod): Boolean {
        if (pod.metadata?.deletionTimestamp != null
            || pod.status?.phase != "Running") {
            return false
        }
        return pod.status?.conditions?.any { condition ->
            condition.type == "Ready" && condition.status == "True"
        } == true
    }

    @Throws(ApiException::class)
    fun list(namespace: String, labelSelector: String = ""): V1PodList {
        return CoreV1Api(client)
            .listNamespacedPod(namespace)
            .labelSelector(labelSelector)
            .execute()
    }

    /**
     * Waits for all pods associated with the given DevWorkspace to be deleted.
     * Returns `true` if all pods are deleted within the timeout, false otherwise.
     *
     * @param namespace the name of the devWorkspace for the pods to be deleted
     * @param workspaceName the name of the devWorkspace for the pods to be deleted
     * @param timeout the max time to wait for the pods to be deleted
     * @param isCancelled lambda to check whether the operation was canceled
     *
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun waitForPodsDeleted(
        namespace: String,
        workspaceName: String,
        timeout: Long, // in seconds
        isCancelled: (() -> Unit)? = null
    ): Boolean {
        val labelSelector = "$WORKSPACE_LABEL_KEY=$workspaceName"
        return withTimeoutOrNull(timeout.seconds) {
            while (true) {
                isCancelled?.invoke()

                val pods = try {
                    list(namespace, labelSelector)
                } catch (e: Exception) {
                    if (e.isCancellationException()) throw e
                    logger.info("Error listing pods for $namespace/$workspaceName: ${e.message}")
                    delay(POD_DELETION_WAIT_DELAY)
                    continue
                }

                isCancelled?.invoke()
                if (pods.items.isEmpty()) {
                    logger.info("All pods for $namespace/$workspaceName have been deleted")
                    return@withTimeoutOrNull true
                }

                logger.debug("Still waiting for ${pods.items.size} pod(s) to be deleted for $namespace/$workspaceName")
                delay(POD_DELETION_WAIT_DELAY)
            }

            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
    }
}
