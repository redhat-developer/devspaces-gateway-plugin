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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

fun String.stripScheme(): String = substringAfter("://", this)

/** Returns the scheme/host/port base URL used for TLS trust lookups. */
fun URI.toServerBaseUrl(): String {
    val port = port
    return if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
}

/**
 * Parses the `key=value` pairs of the URI fragment.
 *
 * Example: `tcp://host:port#cb=261.222.1&jb=25.0.2b329.72` yields
 * `{cb=261.222.1, jb=25.0.2b329.72}`. Values are URL-decoded as UTF-8.
 *
 * @return the fragment parameters, or an empty map when the URI has no fragment
 */
fun URI.fragmentParameters(): Map<String, String> {
    val fragment = rawFragment
        ?: return emptyMap()
    return fragment.split('&')
        .filter { it.contains('=') }
        .associate { param ->
            val key = param.substringBefore('=')
            val value = param.substringAfter('=')
            key to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }
}
