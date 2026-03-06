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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SandboxSignupResponse(
    @SerialName("apiEndpoint")
    val apiEndpoint: String,

    @SerialName("proxyURL")
    val proxyUrl: String? = null,

    @SerialName("clusterName")
    val clusterName: String? = null,

    @SerialName("consoleURL")
    val consoleUrl: String? = null,

    @SerialName("username")
    val username: String,

    @SerialName("compliantUsername")
    val compliantUsername: String? = null,

    @SerialName("status")
    val status: SandboxStatus
)

@Serializable
data class SandboxStatus(
    val ready: Boolean,

    @SerialName("verificationRequired")
    val verificationRequired: Boolean = false,

    val reason: String? = null
)
