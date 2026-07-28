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
package com.redhat.devtools.gateway.openshift.apiclient

import com.redhat.devtools.gateway.auth.tls.TlsContext
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.credentials.AccessTokenAuthentication

/**
 * Builder for token-authenticated API clients.
 * Uses the wizard-negotiated [TlsContext] for TLS trust (CA + session trust + persistent store).
 */
class TokenClientBuilder(
    private val server: String,
    private val token: String,
    private val tlsContext: TlsContext
) : BaseClientBuilder() {
    override fun build(): ApiClient {
        require(token.isNotEmpty()) {
            "Provide either token OR clientCert + clientKey."
        }
        val client = ApiClient(createHttpClient(tlsContext.sslContext, tlsContext.trustManager))
        client.basePath = normalizeBasePath(server)
        AccessTokenAuthentication(token.trim()).provide(client)
        return applyReadTimeout(client)
    }
}
