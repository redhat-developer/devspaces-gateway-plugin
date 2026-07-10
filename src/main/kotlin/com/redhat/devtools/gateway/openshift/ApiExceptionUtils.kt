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

import com.google.gson.Gson
import com.redhat.devtools.gateway.devworkspace.WorkspaceAccessDeniedException
import com.redhat.devtools.gateway.devworkspace.WorkspaceException
import com.redhat.devtools.gateway.devworkspace.WorkspaceNotFoundException
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1Status

fun ApiException.isNotFound(): Boolean {
    return code == 404
        || getStatus()?.code == 404
        || getStatus()?.reason == "NotFound"
}

fun ApiException.isUnauthorized(): Boolean {
    return code == 401 || getStatus()?.code == 401
}

fun ApiException.isForbidden(): Boolean {
    return code == 403 || getStatus()?.code == 403
}

fun ApiException.isDevWorkspaceCrdMissing(): Boolean {
    val status = getStatus() ?: return false
    val message = status.message.orEmpty()
    if (message.contains("could not find the requested resource", ignoreCase = true)
        && message.contains("devworkspaces", ignoreCase = true)
    ) {
        return true
    }
    val details = status.details ?: return false
    return "devworkspaces".equals(details.kind, ignoreCase = true) && details.name.isNullOrBlank()
}

fun ApiException.isDevWorkspaceResourceMissing(name: String): Boolean {
    val status = getStatus() ?: return false
    val message = status.message.orEmpty()
    if (message.contains("\"$name\" not found", ignoreCase = true)) {
        return true
    }
    val details = status.details ?: return false
    return details.name == name && "devworkspaces".equals(details.kind, ignoreCase = true)
}

fun ApiException.isDevWorkspaceAccessDenied(namespace: String): Boolean {
    if (!isForbidden()) return false
    val message = buildString {
        append(getStatus()?.message.orEmpty())
        append(responseBody.orEmpty())
    }
    return message.contains("devworkspaces", ignoreCase = true)
        || message.contains("namespace \"$namespace\"", ignoreCase = true)
        || message.contains("namespaces \"$namespace\"", ignoreCase = true)
}

fun ApiException.toWorkspaceException(
    namespace: String,
    name: String,
    clusterUrl: String,
): WorkspaceException? = when {
    isDevWorkspaceAccessDenied(namespace) -> WorkspaceAccessDeniedException(namespace, name, clusterUrl, this)
    isDevWorkspaceCrdMissing() -> WorkspaceNotFoundException(namespace, name, clusterUrl, this)
    isDevWorkspaceResourceMissing(name) -> WorkspaceNotFoundException(namespace, name, clusterUrl, this)
    isNotFound() -> WorkspaceNotFoundException(namespace, name, clusterUrl, this)
    else -> null
}

/**
 * Converts HTTP status code to human-readable message.
 */
fun ApiException.codeToReasonPhrase(): String {
    val statusMessage = when (code) {
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        408 -> "Request Timeout"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "HTTP Error $code"
    }
    return statusMessage
}

fun ApiException.shouldBeIgnored(): Boolean =
    code == 403 || code == 404
fun ApiException.isRetryable(): Boolean =
    code in setOf(429, 500, 502, 503, 504)

fun ApiException.getStatus(): V1Status? {
    return responseBody.takeIf { it.isNotEmpty() }
        ?.let {
            runCatching { Gson().fromJson(it, V1Status::class.java) }
        }
        ?.getOrNull()
}
