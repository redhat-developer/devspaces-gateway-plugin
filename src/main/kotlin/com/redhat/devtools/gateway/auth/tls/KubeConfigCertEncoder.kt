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
import java.util.Base64

object KubeConfigCertEncoder {

    /**
     * Encodes a certificate exactly as expected by kubeconfig's
     * `certificate-authority-data` field.
     */
    fun encode(certificate: X509Certificate): String {
        val pem = PemUtils.toPem(certificate)
        return Base64.getEncoder()
            .encodeToString(pem.toByteArray(Charsets.UTF_8))
    }
}
