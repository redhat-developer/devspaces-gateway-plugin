/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.auth.code

import kotlinx.coroutines.future.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class AccessTokenResponseJson(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long
)

suspend fun HttpClient.sendGetRequest(
    url: String,
    errorPrefix: String = "Request to $url failed"
): HttpResponse<String> {
    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .GET()
        .build()
    val response = sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
    if (response.statusCode() !in 200..299) {
        error("$errorPrefix: ${response.statusCode()}\n${response.body()}")
    }
    return response
}

suspend fun HttpClient.sendPostRequest(
    url: String,
    authHeader: String,
    formBody: String,
    errorPrefix: String = "Request to $url failed"
): AccessTokenResponseJson {
    val request = HttpRequest.newBuilder()
        .uri(URI(url))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(formBody))
        .build()
    val response = sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
    if (response.statusCode() !in 200..299) {
        error("$errorPrefix: ${response.statusCode()}\n${response.body()}")
    }
    return json.decodeFromString(AccessTokenResponseJson.serializer(), response.body())
}
