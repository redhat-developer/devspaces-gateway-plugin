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
package com.redhat.devtools.gateway.view.steps.auth

/**
 * Enumeration of supported authentication methods.
 */
enum class AuthMethod {
    TOKEN,                  // User token
    CLIENT_CERTIFICATE,     // Client certificate
    OPENSHIFT,              // browser PKCE
    OPENSHIFT_CREDENTIALS,  // username/password
    REDHAT_SSO              // RH SSO (Sandbox)
}
