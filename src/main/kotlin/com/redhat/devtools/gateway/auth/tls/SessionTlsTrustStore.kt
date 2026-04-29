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
import java.util.concurrent.ConcurrentHashMap

class SessionTlsTrustStore {

    private val trusted = ConcurrentHashMap<String, List<X509Certificate>>()

    fun get(serverUrl: String): List<X509Certificate> =
        trusted[serverUrl].orEmpty()

    fun put(serverUrl: String, certificates: List<X509Certificate>) {
        trusted[serverUrl] = certificates
    }
}
