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

import com.redhat.devtools.gateway.openshift.apiclient.ApiClientUtils
import com.redhat.devtools.gateway.util.isCancellationException
import io.kubernetes.client.custom.IOTrio
import io.kubernetes.client.openapi.ApiClient
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PodExecSession(
    private val client: ApiClient,
    namespace: String?,
    podName: String?,
    private val container: String,
    private val command: Array<String>,
    private val timeout: Long,
    private val checkCancelled: (() -> Unit)?
) {
    private val namespace: String = namespace
        ?: throw IOException("Pod namespace is missing")
    private val podName: String = podName
        ?: throw IOException("Pod name is missing")

    suspend fun execute(): String = suspendCancellableCoroutine { cont ->
        val ctx = ExecContext()
        val joinerJob = launchJoiner(ctx, cont)

        var execHandle: ContainerAwareExec.ExecHandle? = null
        cont.invokeOnCancellation {
            ctx.streamsReady.complete(Unit)
            closeQuietly(ctx.io?.stdin)
            closeQuietly(ctx.io?.stdout)
            closeQuietly(ctx.io?.stderr)
            runCatching { joinerJob.cancel("Pods.exec cancellation") }
            execHandle?.let { handle ->
                runCatching {
                    handle.job.cancel(CancellationException("Pods.exec cancellation"))
                    handle.future.cancel(true)
                }
            }
            ctx.scope.cancel()
        }

        try {
            execHandle = runExec(ctx)
        } catch (e: Exception) {
            ctx.streamsReady.complete(Unit)
            ctx.exitCode.completeExceptionally(e)
        }
    }

    private fun launchJoiner(ctx: ExecContext, cont: CancellableContinuation<String>): Job =
        ctx.scope.launch {
            try {
                ctx.streamsReady.await()
                listOfNotNull(ctx.stdoutJobRef.get(), ctx.stderrJobRef.get()).joinAll()
                checkCancelled?.invoke()
                val code = ctx.exitCode.await()

                checkCancelled?.invoke()
                val stderrMsg = ctx.stderr.toString().takeIf { it.isNotBlank() }
                    ?.let { "; stderr: ${it.take(2000)}" }.orEmpty()
                when {
                    code == Int.MAX_VALUE -> {
                        if (cont.isActive) cont.resumeWithException(IOException("Pod exec timed out after ${timeout}s$stderrMsg"))
                    }
                    code != 0 -> {
                        if (cont.isActive) cont.resumeWithException(IOException("Pod exec failed with exit code $code$stderrMsg"))
                    }
                    else -> {
                        val readError = ctx.streamReadError.get()
                        if (readError != null) {
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    IOException(
                                        "Pod exec stream closed before output was fully read$stderrMsg",
                                        readError
                                    )
                                )
                            }
                        } else if (cont.isActive) {
                            cont.resume(ctx.stdout.toString())
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e.isCancellationException()) cont.cancel(e)
                else if (cont.isActive) cont.resumeWithException(e)
            } finally {
                ctx.scope.cancel()
                shutdownExecClient(ctx.execClient)
            }
        }

    private fun runExec(ctx: ExecContext): ContainerAwareExec.ExecHandle =
        ContainerAwareExec(ctx.execClient).containerAwareExec(
            namespace = namespace,
            pod = podName,
            container = container,
            command = command,
            onOpen = { i ->
                ctx.io = i
                launchCheckCancelled(checkCancelled, ctx.scope, i)
                ctx.stdoutJobRef.set(
                    ctx.scope.launch { readStream(i.stdout, ctx.stdout, checkCancelled, ctx.streamReadError) }
                )
                ctx.stderrJobRef.set(
                    ctx.scope.launch { readStream(i.stderr, ctx.stderr, checkCancelled, ctx.streamReadError) }
                )
                ctx.streamsReady.complete(Unit)
            },
            onClosed = { code, _ ->
                ctx.streamsReady.complete(Unit)
                ctx.exitCode.complete(code)
            },
            onError = { err, _ ->
                ctx.exitCode.completeExceptionally(err)
                closeQuietly(ctx.io?.stdout)
                closeQuietly(ctx.io?.stderr)
                ctx.streamsReady.complete(Unit)
            },
            timeoutMs = timeout * 1000,
            tty = false
        )

    private fun launchCheckCancelled(
        checkCancelled: (() -> Unit)?,
        scope: CoroutineScope,
        io: IOTrio
    ) {
        if (checkCancelled == null) return
        scope.launch {
            try {
                while (isActive) {
                    checkCancelled.invoke()
                    @Suppress("ConvertLongToDuration")
                    delay(200)
                }
            } catch (_: Throwable) {
                closeQuietly(io.stdout)
                closeQuietly(io.stderr)
            }
        }
    }

    private fun shutdownExecClient(client: ApiClient) {
        runCatching {
            val executor = client.httpClient.dispatcher.executorService
            executor.shutdownNow()
            executor.awaitTermination(500, TimeUnit.MILLISECONDS)
        }
        runCatching { client.httpClient.connectionPool.evictAll() }
    }

    private fun readStream(
        input: InputStream,
        output: StringBuilder,
        checkCancelled: (() -> Unit)?,
        streamReadError: AtomicReference<IOException?>
    ) {
        try {
            val reader = input.reader(Charsets.UTF_8)
            val buffer = CharArray(4096)
            while (true) {
                checkCancelled?.invoke()
                val read = reader.read(buffer)
                if (read == -1) break
                output.appendRange(buffer, 0, read)
            }
        } catch (e: IOException) {
            // Closed during cancel/timeout/onError — recorded so exit 0 cannot return a partial buffer
            streamReadError.compareAndSet(null, e)
        }
    }

    private fun createIsolatedExecClient(base: ApiClient): ApiClient =
        ApiClientUtils.cloneForExec(base)

    private inner class ExecContext(
        val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        val exitCode: CompletableDeferred<Int> = CompletableDeferred(),
        val streamsReady: CompletableDeferred<Unit> = CompletableDeferred(),
        val stdout: StringBuilder = StringBuilder(),
        val stderr: StringBuilder = StringBuilder(),
        val stdoutJobRef: AtomicReference<Job?> = AtomicReference(null),
        val stderrJobRef: AtomicReference<Job?> = AtomicReference(null),
        val streamReadError: AtomicReference<IOException?> = AtomicReference(null),
        val execClient: ApiClient = createIsolatedExecClient(client),
        var io: IOTrio? = null
    )

}
