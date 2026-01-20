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
package com.redhat.devtools.gateway.auth.server.che

import com.redhat.devtools.gateway.auth.config.AuthType
import com.redhat.devtools.gateway.auth.config.ServerConfig

@Suppress("UNCHECKED_CAST")
internal suspend fun getCheServerConfig(type: AuthType): ServerConfig {
    val endpointName = type.value

    val cheApi = try {
        Class.forName("@eclipse-che.plugin")
    } catch (_: Throwable) {
        throw IllegalStateException("Che plugin API not available")
    }

    // NOTE:
    // JetBrains does not ship Che APIs by default.
    // This code is intentionally reflective to avoid runtime crashes.
    val che = cheApi.getDeclaredConstructor().newInstance()

    // TODO:
    // In STEP 6 we will replace this with proper Che / Dev Spaces APIs
    // once JetBrains Gateway mapping is finalized.

    throw IllegalStateException(
        "Che server config resolution will be finalized in STEP 6"
    )
}
