/*
 * Copyright (c) 2025-2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.auth.server

import com.redhat.devtools.gateway.auth.config.ServerConfig
import java.net.URI

object RedirectUrlBuilder {

    fun signinUrl(serverConfig: ServerConfig, port: Int, nonce: String): URI {
        val base = URI(serverConfig.externalUrl)
        return URI(
            base.scheme,
            base.authority,
            "/signin",
            "nonce=$nonce",
            null
        ).let {
            if (base.port == -1)
                URI(it.scheme, it.userInfo, it.host, port, it.path, it.query, it.fragment)
            else it
        }
    }

    fun callbackUrl(serverConfig: ServerConfig, port: Int): URI {
        val base = URI(serverConfig.externalUrl)
        val path = "/${serverConfig.callbackPath}"

        return URI(base.scheme, base.authority, path, null, null).let {
            if (base.port == -1)
                URI(it.scheme, it.userInfo, it.host, port, it.path, it.query, it.fragment)
            else it
        }
    }
}
