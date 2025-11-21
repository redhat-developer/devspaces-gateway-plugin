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

import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils.toName
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils.toUriWithHost

data class Cluster(
    val name: String,
    val url: String,
    val token: String? = null
) {

    companion object {
        fun fromNameAndUrl(nameAndUrl: String): Cluster? {
            val parsed = getNameAndUrl(nameAndUrl)
            val name = parsed?.first
            val uri = toUriWithHost(parsed?.second)
            return when {
                name != null && uri != null ->
                    Cluster(name, uri.toString())
                uri != null -> {
                    val nameFromUrl = toName(uri) ?: return null
                    Cluster(nameFromUrl, uri.toString())
                }
                else -> null
            }
        }

        private fun getNameAndUrl(nameAndUrl: String): Pair<String?, String?>? {
            // Captures: 1: Name, 2: URL in parentheses, 3: Full URL (if no parens)
            val regex = Regex("^(.+?)\\s*\\((.*?)\\)$|^(.+)$")

            val matchResult = regex.find(nameAndUrl.trim())
                ?: return Pair(nameAndUrl.trim(), nameAndUrl.trim()) // Should not happen with this regex

            val (name, urlInParens, fullUrl) = matchResult.destructured

            val pair = when {
                // "name (url)" OR "name ()"
                name.isNotEmpty() -> {
                    val trimmedName = name.trim()
                    val trimmedUrl = urlInParens.trim()
                    if (trimmedUrl.isEmpty()) {
                        Pair(trimmedName, null)
                    } else {
                        Pair(trimmedName, trimmedUrl)
                    }
                }
                // "url-only" (Matches the second alternative: ^(.+)$)
                fullUrl.isNotEmpty() -> {
                    val trimmedUrl = fullUrl.trim()
                    Pair(null, trimmedUrl)
                }

                else -> null
            }
            return pair
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
