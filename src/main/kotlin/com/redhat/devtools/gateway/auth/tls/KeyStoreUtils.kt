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

import java.security.KeyStore
import java.security.cert.X509Certificate

internal object KeyStoreUtils {

    fun createEmpty(): KeyStore =
        KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
        }

    fun addCertificate(
        keyStore: KeyStore,
        alias: String,
        certificate: X509Certificate
    ) {
        keyStore.setCertificateEntry(alias, certificate)
    }
}
