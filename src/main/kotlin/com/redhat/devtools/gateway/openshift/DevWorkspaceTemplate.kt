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

import java.util.Collections

data class DevWorkspaceTemplate (
    private val metadata: DevWorkspaceTemplateMetadata,
    private val spec: DevWorkspaceTemplateSpec,
) {
    val namespace: String
        get() {
            return metadata.namespace
        }

    val name: String
        get() {
            return metadata.name
        }

    val ownerRefencesUids: List<String>
        get() {
            return metadata.ownerRefencesUids
        }

    val components: Any
        get() {
            return spec.components
        }

    val pluginRegistryUrl: String?
        get() {
            return metadata.pluginRegistryUrl
        }


    companion object {
        fun from(map: Any?) = object {
            val metadata = Utils.getValue(map, arrayOf("metadata")) ?: Collections.emptyMap<String, Any>()
            val spec = Utils.getValue(map, arrayOf("spec")) ?: Collections.emptyMap<String, Any>()

            val data = DevWorkspaceTemplate(
                DevWorkspaceTemplateMetadata.from(metadata),
                DevWorkspaceTemplateSpec.from(spec)
            )
        }.data
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DevWorkspaceTemplate

        if (metadata.name != other.name) return false
        if (metadata.namespace != other.namespace) return false
        if (metadata.pluginRegistryUrl != other.pluginRegistryUrl) return false
        if (metadata.ownerRefencesUids != other.ownerRefencesUids) return false

        return true
    }

    override fun hashCode(): Int {
        var result = metadata.hashCode()
        result = 31 * result + spec.hashCode()
        return result
    }
}

data class DevWorkspaceTemplateMetadata(
    val name: String,
    val namespace: String,
    val pluginRegistryUrl: String?,
    val ownerRefencesUids: List<String>
) {
    companion object {
        fun from(map: Any) = object {
            val name = Utils.getValue(map, arrayOf("name"))
            val namespace = Utils.getValue(map, arrayOf("namespace"))
            val pluginRegistryUrl = Utils.getValue(map, arrayOf("annotations", "che.eclipse.org/plugin-registry-url"))

            @Suppress("UNCHECKED_CAST")
            val ownerRefs = Utils.getValue(map, arrayOf("ownerReferences")) as? List<Map<String, Any>>
            val ownerRefUids: List<String> = ownerRefs
                ?.filter {
                    (it["apiVersion"] as? String)?.equals("workspace.devfile.io/v1alpha2", ignoreCase = true) == true &&
                            (it["kind"] as? String)?.equals("DevWorkspace", ignoreCase = true) == true
                }
                ?.mapNotNull { it["uid"] as? String }
                ?: emptyList()

            val data = DevWorkspaceTemplateMetadata(
                name as String,
                namespace as String,
                pluginRegistryUrl as String?,
                ownerRefUids
            )
        }.data
    }
}

data class DevWorkspaceTemplateSpec(
    val components: List<Map<String, Any>>
) {
    companion object {
        fun from(map: Any) = object {
            val rawComponents: Any? = Utils.getValue(map, arrayOf("components"))
            val components = if (rawComponents is List<*>) {
                rawComponents.filterIsInstance<Map<String, Any>>()
            } else {
                emptyList()
            }
            val data = DevWorkspaceTemplateSpec(components)
        }.data
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (other !is DevWorkspaceTemplateSpec) return false

        return components == other.components
    }

    override fun hashCode(): Int {
        return components.hashCode()
    }
}
