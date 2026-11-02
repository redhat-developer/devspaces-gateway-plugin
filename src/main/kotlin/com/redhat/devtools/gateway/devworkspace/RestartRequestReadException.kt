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
package com.redhat.devtools.gateway.devworkspace

/**
 * Signals that the restart request could not be read after retrying.
 *
 * Unlike [InvalidRestartRequestException] the request itself is not known to be invalid: a
 * transient API failure prevented reading it. The restart is aborted before the IDE is closed.
 */
internal class RestartRequestReadException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
