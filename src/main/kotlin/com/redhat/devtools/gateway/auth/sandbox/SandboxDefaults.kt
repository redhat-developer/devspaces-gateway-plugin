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
package com.redhat.devtools.gateway.auth.sandbox

object SandboxDefaults {

    /**
     * Matches VS Code default:
     * openshiftToolkit.sandboxApiHostUrl
     */
    const val SANDBOX_API_BASE_URL =
        "https://registration-service-toolchain-host-operator.apps.sandbox.x8i5.p1.openshiftapps.com"

    /**
     * Matches VS Code default:
     * openshiftToolkit.sandboxApiTimeout
     */
    const val SANDBOX_API_TIMEOUT_MS: Long = 100_000
}
