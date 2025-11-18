/*
 * Copyright (c) 2025 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.openshift

import java.net.URI
import java.net.URISyntaxException

data class Cluster(
    val name: String,
    val url: String,
    val token: String? = null
) {

    companion object {
        fun fromUrl(url: String): Cluster? {
            return try {
                val name = toName(url)
                if (name == null) {
                    null
                } else {
                    Cluster(name, url) // Use host directly from URI
                }
            } catch(_: URISyntaxException) {
                null
            }
        }

        private fun toName(url: String): String? {
            return try {
                val uri = URI(url)
                uri.host
            } catch (_: URISyntaxException) {
                null
            }
        }
    }

    val id: String
        get() {
            return "$name@${
                url
                    .removePrefix("https://")
                    .removePrefix("http://")
            }"
        }

    override fun toString(): String {
        return "$name ($url)"
    }
}
