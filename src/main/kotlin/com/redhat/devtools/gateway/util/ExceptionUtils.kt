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
package com.redhat.devtools.gateway.util

import com.google.gson.Gson
import io.kubernetes.client.openapi.ApiException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException


fun Throwable.rootMessage(): String {
    var cause: Throwable? = this
    while (cause?.cause != null) {
        cause = cause.cause
    }
    return cause?.message?.trim()
        ?: messageWithoutPrefix()
        ?: "Unknown error"
}

fun Throwable.messageWithoutPrefix(): String? {
    return message?.trim()
        ?: message?.substringAfter(":")?.trim()
}

fun Throwable.message(): String {
    return if (this is ApiException) {
        message()
    } else {
        message.orEmpty()
    }
}

fun ApiException.message(): String {
    val response = Gson().fromJson(responseBody, Map::class.java)
    val msg = try {
        response["message"]?.toString()
    } catch (e: Exception) {
        e.rootMessage()
    }
    return "Reason: $msg"
}

fun Throwable.isTimeoutException(): Boolean = (this is TimeoutCancellationException || this is TimeoutException )

fun Throwable.isCancellationException(): Boolean = (this is CancellationException && !isTimeoutException() )
