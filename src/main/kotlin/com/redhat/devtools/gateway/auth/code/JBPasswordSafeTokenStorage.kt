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
package com.redhat.devtools.gateway.auth.code

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class JBPasswordSafeTokenStorage : SecureTokenStorage {

    private val attributes = CredentialAttributes(
        generateServiceName(
            "RedHatGatewayPlugin",
            "RedHatAuthToken"
        )
    )

    override suspend fun saveToken(token: TokenModel) {
        val json = Json.encodeToString(token)

        val credentials = Credentials(
            "redhat",
            json
        )

        withContext(Dispatchers.IO) {
            PasswordSafe.instance.set(attributes, credentials)
        }
    }

    override suspend fun loadToken(): TokenModel? {
        val credentials = withContext(Dispatchers.IO) {
            PasswordSafe.instance.get(attributes)
        } ?: return null
        val json = credentials.getPasswordAsString() ?: return null
        return Json.decodeFromString(json)
    }

    override suspend fun clearToken() {
        withContext(Dispatchers.IO) {
            PasswordSafe.instance.set(attributes, null)
        }
    }
}

