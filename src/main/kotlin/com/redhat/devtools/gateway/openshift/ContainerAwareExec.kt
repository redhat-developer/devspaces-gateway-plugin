/*
 * Copyright (c) 2025 Red Hat, Inc.
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

import com.redhat.devtools.gateway.util.isCancellationException
import io.kubernetes.client.Exec
import io.kubernetes.client.custom.IOTrio
import io.kubernetes.client.openapi.ApiClient
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.function.BiConsumer
import java.util.function.Consumer

class ContainerAwareExec(
    client: ApiClient,
    private val parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Exec(client) {

    data class ExecHandle(
        val future: CompletableFuture<Int>,
        val job: Job,
        val io: IOTrio
    )

    @Throws(IOException::class)
    fun containerAwareExec(
        namespace: String,
        pod: String,
        container: String,
        command: Array<String>,
        onOpen: Consumer<IOTrio>?,
        onClosed: BiConsumer<Int, IOTrio>?,
        onError: BiConsumer<Throwable, IOTrio>?,
        timeoutMs: Long?,
        tty: Boolean
    ): ExecHandle {

        val io = IOTrio()
        val future = CompletableFuture<Int>()
        var process: Process? = null

        val job = parentScope.launch {
            try {
                process = super.exec(
                    namespace,
                    pod,
                    arrayOf(*command),
                    container,
                    true,
                    tty
                )

                io.stdin = process!!.outputStream
                io.stdout = process.inputStream
                io.stderr = process.errorStream

                onOpen?.accept(io)

                val exitCode = withTimeoutOrNull(timeoutMs ?: Long.MAX_VALUE) {
                    while (isActive) {
                        if (!process.isAlive) {
                            return@withTimeoutOrNull process.exitValue()
                        }
                        delay(50)
                    }
                    throw CancellationException()
                }

                val finalCode =
                    if (exitCode == null) {
                        safeTerminateProcess(process)
                        Int.MAX_VALUE
                    } else exitCode

                onClosed?.accept(finalCode, io)
                future.complete(finalCode)

            } catch (t: Throwable) {
                onError?.accept(t, io)
                future.completeExceptionally(t)
                throw t
            }
        }

        job.invokeOnCompletion { cause ->
            if (cause?.isCancellationException() == true) {
                process?.let { safeTerminateProcess(it) }
                future.cancel(true)
            }
        }

        return ExecHandle(future, job, io)
    }

    private fun safeTerminateProcess(process: Process) {
        try { process.inputStream.close() } catch (_: Throwable) {}
        try { process.errorStream.close() } catch (_: Throwable) {}
        try { process.outputStream.close() } catch (_: Throwable) {}
        try { process.destroyForcibly() } catch (_: Throwable) {}
    }
}
