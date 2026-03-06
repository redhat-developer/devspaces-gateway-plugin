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
package com.redhat.devtools.gateway.auth.oidc

import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.openid.connect.sdk.op.OIDCProviderConfigurationRequest
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata

class OidcProviderMetadataResolver(
    authUrl: String
) {
    private val issuer = Issuer(authUrl)

    @Volatile
    private var cached: OIDCProviderMetadata? = null

    suspend fun resolve(): OIDCProviderMetadata {
        cached?.let { return it }

        val request = OIDCProviderConfigurationRequest(issuer)
        val httpResponse = request.toHTTPRequest().send()
        val metadata = OIDCProviderMetadata.parse(httpResponse.bodyAsJSONObject)

        cached = metadata
        return metadata
    }
}