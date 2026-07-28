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
package com.redhat.devtools.gateway.util

import java.net.URI

fun String.stripScheme(): String = substringAfter("://", this)

/** Returns the scheme/host/port base URL used for TLS trust lookups. */
fun URI.toServerBaseUrl(): String {
    val port = port
    return if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
}
