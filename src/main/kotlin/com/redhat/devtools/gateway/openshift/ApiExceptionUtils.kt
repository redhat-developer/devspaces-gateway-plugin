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

import io.kubernetes.client.openapi.ApiException

fun ApiException.isNotFound(): Boolean {
    return code == 404
}

fun ApiException.isUnauthorized(): Boolean {
    return code == 401
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