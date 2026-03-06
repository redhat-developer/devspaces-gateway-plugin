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
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.redhat.devtools.gateway.auth.code

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class IdeaSecureTokenStorage : SecureTokenStorage {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val attributes = CredentialAttributes(
        "com.redhat.devtools.gateway.auth.sso"
    )

    override suspend fun saveToken(token: TokenModel) {
        val serialized = json.encodeToString(token)

        PasswordSafe.instance.set(
            attributes,
            Credentials("sso", serialized)
        )
    }

    override suspend fun loadToken(): TokenModel? {
        val credentials = PasswordSafe.instance.get(attributes)
            ?: return null

        val raw = credentials.password?.toString()
            ?: return null

        return runCatching {
            json.decodeFromString<TokenModel>(raw)
        }.getOrNull()
    }

    override suspend fun clearToken() {
        PasswordSafe.instance.set(attributes, null)
    }
}
