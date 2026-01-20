/*
 * Copyright (c) 2025-2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.auth.server

import com.redhat.devtools.gateway.auth.code.Parameters
import com.redhat.devtools.gateway.auth.config.ServerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import com.sun.net.httpserver.HttpServer

class OAuthCallbackServer(
    private val serverConfig: ServerConfig
) : CallbackServer {

    private var server: HttpServer? = null
    private var callbackDeferred: CompletableDeferred<Parameters>? = null

    override suspend fun start(): Int {
        if (server != null) return server!!.address.port

        callbackDeferred = CompletableDeferred()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", serverConfig.port ?: 0), 0)
        server!!.executor = Executors.newSingleThreadExecutor()

        server!!.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            if (path == "/${serverConfig.callbackPath}") {
                val query = exchange.requestURI.query ?: ""
                val params: Parameters = query.split("&")
                    .mapNotNull {
                        val pair = it.split("=", limit = 2)
                        if (pair.isNotEmpty()) pair[0] to pair.getOrElse(1) { "" } else null
                    }
                    .associate { it.first to URLDecoder.decode(it.second, "UTF-8") }
                callbackDeferred?.complete(params)

                val response = "Authentication successful. You may close this window."
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            } else if (path == "/signin") {
                val response = "Sign-in initialized. You may continue."
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            } else {
                exchange.sendResponseHeaders(404, 0)
                exchange.responseBody.close()
            }
        }

        server!!.start()
        return server!!.address.port
    }

    override suspend fun stop() {
        server?.stop(0)
        server = null
        callbackDeferred?.cancel()
        callbackDeferred = null
    }

    override suspend fun awaitCallback(timeoutMs: Long): Parameters? =
        withTimeoutOrNull(timeoutMs) { callbackDeferred?.await() }
}
