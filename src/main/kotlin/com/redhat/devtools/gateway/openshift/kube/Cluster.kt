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
package com.redhat.devtools.gateway.openshift.kube

data class Cluster(
    val name: String,
    val url: String
) {
    companion object {
        private val SCHEMA_REGEX = Regex("^https?://")
        private val PATH_REGEX = Regex("/.*$")

        fun fromString(string: String): Cluster? {
            return if (isUrl(string)) {
                fromUrl(string)
            } else {
                val match = getUrlAndNameMatch(string)
                if (match != null) {
                    val (name, url) = match.destructured
                    Cluster(name, url)
                } else {
                    null
                }
            }
        }

        fun toString(cluster: Cluster?): String {
            return if (cluster == null) {
                ""
            } else {
                "${cluster.name} (${cluster.url})"
            }
        }

        private fun isUrl(text: String): Boolean {
            return text.startsWith("https://") || text.startsWith("http://")
        }

        private fun fromUrl(url: String): Cluster {
            val name = url
                .replace(SCHEMA_REGEX, "")
                .replace(PATH_REGEX, "")
            return Cluster(name, url)
        }

        private fun getUrlAndNameMatch(text: String): MatchResult? {
            return Regex("""^(.+)\s*\((https?://.+)\)$""").find(text)
        }
    }
}