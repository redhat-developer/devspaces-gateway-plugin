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
package com.redhat.devtools.gateway.kubeconfig

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import io.kubernetes.client.persister.ConfigPersister
import java.io.File

class BlockStyleFilePersister(private val file: File) : ConfigPersister {

    @Throws(java.io.IOException::class)
    override fun save(
        contexts: ArrayList<Any?>,
        clusters: ArrayList<Any?>,
        users: ArrayList<Any?>,
        preferences: Any?,
        currentContext: String?
    ) {
        val config = mapOf(
            "apiVersion" to "v1",
            "kind" to "Config",
            "current-context" to currentContext,
            "preferences" to preferences,

            "clusters" to clusters,
            "contexts" to contexts,
            "users" to users,
        )

        synchronized(file) {
            val yamlFactory = YAMLFactory().apply {
                configure(YAMLGenerator.Feature.MINIMIZE_QUOTES, true)
                configure(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR, true)
            }
            val mapper = ObjectMapper(yamlFactory)
            mapper.writeValue(file, config)
        }
    }
}