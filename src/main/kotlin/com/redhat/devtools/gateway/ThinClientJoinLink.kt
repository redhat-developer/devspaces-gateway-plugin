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
 * the pod, and lets that link be varied for reconnection.
 *
 * The Guest's same-link no-op guard ignores an `UpdateLink` message whose link equals the previous
 * one while a project window is open. It compares the link text, so [withReconnectNonce] makes two
 * prods distinct by adding a fragment parameter. Fragment parameters survive the Guest's
 * `patchConnectionLink`, which merges the new link's fragment parameters onto its gateway URI.
 *
 * Stateless pure transformations: no dependencies, no lifecycle, so an object (one instance) over a
 * class the caller would have to instantiate.
 */
internal object ThinClientJoinLink {

    /** The port the IDE server listens on inside the DevWorkspace pod. */
    const val REMOTE_IDE_PORT: Int = 5990

    /** Fragment parameter carrying the reconnect nonce. */
    const val RECONNECT_PARAMETER: String = "gatewayReconnect"

    private const val MIN_PORT = 1
    private const val MAX_PORT = 65_535
    private const val PARAMETER_SEPARATOR = '&'
    private const val NAME_VALUE_SEPARATOR = '='

    /**
     * Replaces the IDE server port in [joinLink] with [localPort], preserving scheme, host, path,
     * query, and fragment. Fails fast when the link does not address [REMOTE_IDE_PORT]: a malformed
     * link must surface as an error instead of silently pointing the Guest at the wrong port.
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
        return URI(
            joinLink.scheme,
            localAuthority,
            joinLink.rawPath,
            joinLink.rawQuery,
            joinLink.rawFragment
        )
    }

    /**
     * Adds or replaces the [RECONNECT_PARAMETER] fragment parameter with [nonce], so two links built
     * from the same join link are never byte-identical while all other fragment parameters survive.
     */
    fun withReconnectNonce(link: URI, nonce: Long): URI {
        val fragment = withFragmentParameter(
            link.rawFragment,
            RECONNECT_PARAMETER,
            nonce.toString()
        )
        return URI(link.scheme, link.rawAuthority, link.rawPath, link.rawQuery, fragment)
    }

    /** Replaces [name] in place when present, appends it otherwise; keeps every other parameter. */
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
