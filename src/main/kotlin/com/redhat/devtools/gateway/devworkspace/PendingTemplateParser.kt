/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.devworkspace

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Parses the versioned payload the remote IDE publishes in the
 * [DevWorkspace.PENDING_TEMPLATE_KEY] annotation.
 *
 * The payload is a versioned envelope so that a Gateway can refuse content written by a
 * reachable editor image it does not understand:
 *
 * ```json
 * { "version": 1, "template": { "components": [ ... ] } }
 * ```
 */
internal object PendingTemplateParser {

    /** The payload version this Gateway understands. */
    const val VERSION: Int = 1

    private val mapper = ObjectMapper()
    private val PAYLOAD_TYPE = object : TypeReference<Map<String, Any?>>() {}

    /**
     * @param content the annotation value, or `null` when the annotation is absent
     * @return the template to patch, or `null` when the annotation is absent
     * @throws InvalidRestartRequestException when the annotation is present but blank or its
     *         payload is malformed, empty, or of an unsupported version
     */
    fun parse(content: String?): Map<String, Any?>? {
        if (content == null) {
            return null
        }
        if (content.isBlank()) {
            throw InvalidRestartRequestException("Devfile template payload is blank")
        }
        val resource = toMap(content)
        requireKnownVersion(resource)
        val template = resource["template"] as? Map<*, *>
            ?: throw InvalidRestartRequestException("Devfile template payload has no template")
        if (template.isEmpty()) {
            throw InvalidRestartRequestException("Devfile template payload has an empty template")
        }
        return template.entries.associate { it.key.toString() to it.value }
    }

    private fun requireKnownVersion(payload: Map<String, Any?>) {
        val version = (payload["version"] as? Number)?.toInt()
        if (version != VERSION) {
            throw InvalidRestartRequestException(
                "Unsupported devfile template payload version: $version"
            )
        }
    }

    private fun toMap(content: String): Map<String, Any?> {
        return try {
            mapper.readValue(content, PAYLOAD_TYPE)
        } catch (e: JsonProcessingException) {
            throw InvalidRestartRequestException("Invalid devfile template payload", e)
        }
    }
}
