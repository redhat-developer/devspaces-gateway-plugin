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
package com.redhat.devtools.gateway.auth.tls

/** Identifies which cluster endpoint triggered a TLS trust prompt or handshake. */
enum class TlsEndpointKind(val label: String) {
    UNKNOWN("server"),
    API_SERVER("OpenShift API server"),
    OAUTH("OpenShift OAuth endpoint"),
}
