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

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore

class PersistentKeyStore(
    private val path: Path,
    private val password: CharArray = CharArray(0)
) {

    fun loadOrCreate(): KeyStore {
        val keyStore = KeyStore.getInstance("PKCS12")

        if (Files.exists(path)) {
            Files.newInputStream(path).use {
                keyStore.load(it, password)
            }
        } else {
            keyStore.load(null, password)
        }

        return keyStore
    }

    fun save(keyStore: KeyStore) {
        Files.createDirectories(path.parent)

        Files.newOutputStream(path).use {
            keyStore.store(it, password)
        }
    }
}
