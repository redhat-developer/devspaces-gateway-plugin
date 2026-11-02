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
import com.fasterxml.jackson.databind.DeserializationFeature
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

    /**
     * Rejects payloads carrying more than one JSON value, so a concatenated or partially
     * overwritten annotation fails instead of silently applying only the first envelope.
     */
    private val mapper = ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
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
        val payload = toMap(content)
        requireKnownVersion(payload)
        return requireTemplate(payload)
    }

    private fun requireKnownVersion(payload: Map<String, Any?>) {
        val version = payload["version"] as? Int
        if (version != VERSION) {
            throw InvalidRestartRequestException(
                "Unsupported devfile template payload version: ${payload["version"] ?: "<missing>"}"
            )
        }
    }

    private fun requireTemplate(payload: Map<String, Any?>): Map<String, Any?> {
        val template = payload["template"] as? Map<*, *>
            ?: throw InvalidRestartRequestException("Devfile template payload has no template")
        if (template.isEmpty()) {
            throw InvalidRestartRequestException("Devfile template payload has an empty template")
        }
        requireComponentsIsList(template)
        return template.entries.associate { it.key.toString() to it.value }
    }

    /**
     * Rejects a payload whose `components` value is present but not a list, so a malformed template
     * fails before the workspace is torn down. An absent or `null` `components` is accepted.
     */
    private fun requireComponentsIsList(template: Map<*, *>) {
        val components = template["components"] ?: return
        if (components !is List<*>) {
            throw InvalidRestartRequestException("Devfile template payload has non-list components")
        }
    }

    private fun toMap(content: String): Map<String, Any?> {
        val payload: Map<String, Any?>? = try {
            mapper.readValue(content, PAYLOAD_TYPE)
        } catch (e: JsonProcessingException) {
            throw InvalidRestartRequestException("Invalid devfile template payload", e)
        }
        return payload
            ?: throw InvalidRestartRequestException("Devfile template payload is null")
    }
}
