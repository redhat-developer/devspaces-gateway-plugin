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

import com.intellij.openapi.progress.ProcessCanceledException
import com.redhat.devtools.gateway.auth.session.SsoLoginException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

fun Throwable.messageWithoutPrefix(): String? {
    return message?.trim()
        ?: message?.substringAfter(":")?.trim()
}

fun Throwable.isTimeoutException(): Boolean = (this is TimeoutCancellationException || this is TimeoutException )

fun Throwable.isCancellationException(): Boolean =
    this is ProcessCanceledException
        || (this is CancellationException && !isTimeoutException())

fun Throwable.isLoginUserCancelled(): Boolean =
    generateSequence(this) { it.cause }.any { it is SsoLoginException.Cancelled }
