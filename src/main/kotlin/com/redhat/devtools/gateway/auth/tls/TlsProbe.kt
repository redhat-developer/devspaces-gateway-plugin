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
package com.redhat.devtools.gateway.auth.tls

import java.net.URI
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

object TlsProbe {

    fun connect(serverUri: URI, sslContext: SSLContext) {
        val socketFactory = sslContext.socketFactory
        val port = if (serverUri.port != -1) serverUri.port else 443

        (socketFactory.createSocket(serverUri.host, port) as SSLSocket).use { socket ->
            socket.startHandshake()
        }
    }
}
