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
package com.redhat.devtools.gateway

/**
 * Base of the thin-client connect failures, classified by the phase that failed so that
 * error reporting states the actual cause instead of a generic "workspace IDE is not
 * ready" (which wrongly blamed the workspace for client-side download and launch
 * failures).
 *
 * [title] is the error dialog title the failure is reported with, so that every surface
 * (wizard dialog, connection provider) formats these failures the same way.
 */
sealed class ThinClientConnectException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {

    abstract val title: String
}

/** The workspace IDE reported no join link — it is not ready to be connected to. */
class ThinClientNotReadyException(message: String) : ThinClientConnectException(message) {
    override val title: String = "Connection Error"
}

/** The JetBrains Client could not be downloaded during a first-time connect. */
class ThinClientDownloadException(message: String, cause: Throwable?) : ThinClientConnectException(message, cause) {
    override val title: String = "Cannot Download JetBrains Client"
}

/** The client was started but closed or failed to open the project before connecting. */
class ThinClientLaunchException(message: String) : ThinClientConnectException(message) {
    override val title: String = "Cannot Start JetBrains Client"
}
