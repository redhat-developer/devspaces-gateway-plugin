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

import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

class CapturingTrustManager(
    private val failIfUntrusted: Boolean = false
) : X509TrustManager {

    @Volatile
    var serverCertificateChain: Array<X509Certificate>? = null
        private set

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        serverCertificateChain = chain
        if (failIfUntrusted) {
            throw SSLHandshakeException("Forced handshake failure for certificate testing")
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
