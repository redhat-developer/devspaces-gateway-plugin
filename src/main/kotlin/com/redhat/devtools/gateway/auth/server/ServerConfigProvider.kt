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
package com.redhat.devtools.gateway.auth.server

import com.redhat.devtools.gateway.auth.config.AuthType
import com.redhat.devtools.gateway.auth.config.ServerConfig
import com.redhat.devtools.gateway.auth.server.che.getCheServerConfig

object ServerConfigProvider {

    suspend fun getServerConfig(type: AuthType): ServerConfig {
        return if (isCheEnvironment()) {
            getCheServerConfig(type)
        } else {
            getLocalServerConfig(type)
        }
    }

    private fun isCheEnvironment(): Boolean =
        System.getenv("CHE_WORKSPACE_ID") != null
}
