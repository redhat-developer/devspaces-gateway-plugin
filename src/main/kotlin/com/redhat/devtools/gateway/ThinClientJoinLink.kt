/*
 * Copyright (c) 2024-2026 Red Hat, Inc.
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

import java.net.URI

/**
 * Rewrites the join link the IDE server reports so the Guest dials a local port-forward instead of
 * the pod; lets that link be varied for reconnection.
 *
 * Guest's same-link no-op guard ignores an `UpdateLink` whose link equals the previous one while a
 * project window is open. It compares link text, so [withReconnectNonce] makes two reconnects
 * distinct via a fragment parameter. Fragment parameters survive Guest's `patchConnectionLink`,
 * which merges the new link's fragment parameters onto its gateway URI.
 *
 * Stateless pure transformations: no dependencies, no lifecycle → object over instantiable class.
 */
internal object ThinClientJoinLink {

    /** Port IDE server listens on inside the DevWorkspace pod. */
    const val REMOTE_IDE_PORT: Int = 5990

    /** Fragment parameter carrying the reconnect nonce. */
    const val RECONNECT_PARAMETER: String = "gatewayReconnect"

    private const val MIN_PORT = 1
    private const val MAX_PORT = 65_535
    private const val PARAMETER_SEPARATOR = '&'
    private const val NAME_VALUE_SEPARATOR = '='
    private const val SCHEME_SUFFIX = ":"
    private const val AUTHORITY_PREFIX = "//"
    private const val QUERY_PREFIX = "?"
    private const val FRAGMENT_PREFIX = "#"

    /**
     * Replaces IDE server port in [joinLink] with [localPort], preserving scheme, host, path, query,
     * fragment. Fails fast when link does not address [REMOTE_IDE_PORT]: malformed link must surface
     * as error instead of silently pointing Guest at the wrong port.
     */
    fun toLocal(joinLink: URI, localPort: Int): URI {
        require(localPort in MIN_PORT..MAX_PORT) {
            "Local port must be a valid TCP port, was $localPort"
        }
        val authority = requireNotNull(joinLink.rawAuthority) {
            "Join link has no authority, cannot locate the IDE server port: $joinLink"
        }
        val remotePortSuffix = ":$REMOTE_IDE_PORT"
        require(joinLink.port == REMOTE_IDE_PORT && authority.endsWith(remotePortSuffix)) {
            "Join link does not address the IDE server port $REMOTE_IDE_PORT: $joinLink"
        }
        val localAuthority = authority.dropLast(remotePortSuffix.length) + ":$localPort"
        return rebuild(
            scheme = joinLink.scheme,
            authority = localAuthority,
            path = joinLink.rawPath,
            query = joinLink.rawQuery,
            fragment = joinLink.rawFragment
        )
    }

    /**
     * Adds or replaces [RECONNECT_PARAMETER] fragment parameter with [nonce] → links built from the
     * same join link never byte-identical; all other fragment parameters survive.
     */
    fun withReconnectNonce(link: URI, nonce: Long): URI {
        val fragment = withFragmentParameter(
            link.rawFragment,
            RECONNECT_PARAMETER,
            nonce.toString()
        )
        return rebuild(
            scheme = link.scheme,
            authority = link.rawAuthority,
            path = link.rawPath,
            query = link.rawQuery,
            fragment = fragment
        )
    }

    /**
     * Builds a URI from raw components, keeping their existing percent escapes untouched.
     *
     * The multi-argument `URI(scheme, authority, path, query, fragment)` constructor treats its
     * arguments as decoded text and re-encodes what looks like an escape: `%20` would become
     * `%2520` and the Guest would dial a different link. Assembling the text and parsing it as a
     * whole preserves every component byte for byte.
     */
    private fun rebuild(
        scheme: String?,
        authority: String?,
        path: String?,
        query: String?,
        fragment: String?
    ): URI {
        val text = buildString {
            scheme?.let { append(it).append(SCHEME_SUFFIX) }
            authority?.let { append(AUTHORITY_PREFIX).append(it) }
            path?.let(::append)
            query?.let { append(QUERY_PREFIX).append(it) }
            fragment?.let { append(FRAGMENT_PREFIX).append(it) }
        }
        return URI(text)
    }

    /** Replaces [name] in place if present, appends otherwise; keeps every other parameter. */
    private fun withFragmentParameter(fragment: String?, name: String, value: String): String {
        val parameters = fragment.orEmpty()
            .split(PARAMETER_SEPARATOR)
            .filter { it.isNotEmpty() }
        var isPresent = false
        val updated = parameters.map { parameter ->
            if (parameter.substringBefore(NAME_VALUE_SEPARATOR) == name) {
                isPresent = true
                "$name$NAME_VALUE_SEPARATOR$value"
            } else {
                parameter
            }
        }
        val withNonce = if (isPresent) updated else updated + "$name$NAME_VALUE_SEPARATOR$value"
        return withNonce.joinToString(PARAMETER_SEPARATOR.toString())
    }
}
