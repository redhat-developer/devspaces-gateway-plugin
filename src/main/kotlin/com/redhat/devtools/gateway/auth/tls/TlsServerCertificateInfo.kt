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

data class TlsServerCertificateInfo(
    val serverUrl: String,
    val certificateChain: List<X509Certificate>,
    val fingerprintSha256: String,
    val problem: TlsTrustProblem
)
