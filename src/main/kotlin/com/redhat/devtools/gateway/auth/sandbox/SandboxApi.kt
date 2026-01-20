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
package com.redhat.devtools.gateway.auth.sandbox

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class SandboxApi(
    private val baseUrl: String,
    private val timeoutMs: Long
) {

    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun getSignUpStatus(ssoToken: String): SandboxSignupResponse? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/v1/signup"))
            .header("Authorization", "Bearer $ssoToken")
            .GET()
            .build()

        val response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        )

        if (response.statusCode() != 200) {
            return null
        }

        return json.decodeFromString(response.body())
    }

    fun signUp(ssoToken: String): Boolean {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/v1/signup"))
            .header("Authorization", "Bearer $ssoToken")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.discarding()
        )

        return response.statusCode() in 200..299
    }
}
