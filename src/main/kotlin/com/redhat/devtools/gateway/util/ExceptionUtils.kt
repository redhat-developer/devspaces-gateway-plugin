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

import com.redhat.devtools.gateway.auth.session.SsoLoginException
import com.redhat.devtools.gateway.server.ServerContainerNotFoundException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

fun Throwable.messageWithoutPrefix(): String? {
    return message?.trim()
        ?: message?.substringAfter(":")?.trim()
}

fun Throwable.isTimeoutException(): Boolean = (this is TimeoutCancellationException || this is TimeoutException )

fun Throwable.isCancellationException(): Boolean = (this is CancellationException && !isTimeoutException() )

/** Frames a compact caller stack shows: enough to name the trigger, not a stack dump. */
private const val CALLER_FRAMES = 4

/**
 * The current call stack as compact `Class.method` frames, for log lines that must attribute an
 * action - a cancel, a teardown - without dumping a full stack trace.
 *
 * Frames of [ownClassName] are skipped, so the first entry is the code that called into it.
 */
fun callerFrames(ownClassName: String): String =
    // only the trace is used here; the message just avoids a bare default-constructed exception.
    // The first frame is this function; the frames of [ownClassName] are the code being logged.
    Exception("caller frames").stackTrace
        .drop(1)
        .dropWhile { it.className == ownClassName }
        .take(CALLER_FRAMES)
        .joinToString(" <- ") { frame ->
            "${frame.className.substringAfterLast('.')}.${frame.methodName}"
        }

/**
 * Returns true if this throwable or any of its causes is a `ServerContainerNotFoundException`.
 * Traverses the exception chain to determine if the root cause is a missing server container.
 *
 * @return true if a `ServerContainerNotFoundException` is found in the exception chain, false otherwise
 */
fun Throwable.isServerContainerNotFound(): Boolean =
    generateSequence(this) { it.cause }.any { it is ServerContainerNotFoundException }

fun Throwable.isLoginUserCancelled(): Boolean =
    generateSequence(this) { it.cause }.any { it is SsoLoginException.Cancelled }

fun Throwable.isTlsRelated(): Boolean =
    generateSequence(this) { it.cause }.any { throwable ->
        val message = throwable.message.orEmpty()
        val className = throwable::class.java.name
        className.contains("SSL", ignoreCase = true) ||
            className.contains("Tls", ignoreCase = true) ||
            message.contains("PKIX", ignoreCase = true) ||
            message.contains("certificate", ignoreCase = true) ||
            message.contains("handshake", ignoreCase = true)
    }
