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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DevWorkspaceTemplateTest {

    @Test
    fun `equals and hashCode are consistent when metadata matches but spec differs`() {
        // given
        val template1 = template(components = listOf(mapOf("volume" to mapOf("name" to "idea-server"))))
        val template2 = template(components = listOf(mapOf("volume" to mapOf("name" to "vscode-server"))))

        // when/then
        assertThat(template1).isEqualTo(template2)
        assertThat(template1.hashCode()).isEqualTo(template2.hashCode())
    }

    @Test
    fun `templates differing in any metadata field are not equal and hash differently`() {
        // given
        val template = template()
        val variants = listOf(
            template(name = "other-template"),
            template(namespace = "other-ns"),
            template(pluginRegistryUrl = "https://registry.example.com"),
            template(ownerReferencesUids = listOf("other-owner-uid"))
        )

        // when/then
        assertThat(variants).allSatisfy { variant ->
            assertThat(variant).isNotEqualTo(template)
            assertThat(variant.hashCode()).isNotEqualTo(template.hashCode())
        }
    }

    @Test
    fun `templates with equal metadata are equal and hash equally`() {
        // given
        val template1 = template(ownerReferencesUids = listOf("owner-a", "owner-b"))
        val template2 = template(ownerReferencesUids = listOf("owner-a", "owner-b"))

        // when/then
        assertThat(template1).isEqualTo(template2)
        assertThat(template1.hashCode()).isEqualTo(template2.hashCode())
    }

    private fun template(
        name: String = "test-template",
        namespace: String = "test-ns",
        pluginRegistryUrl: String? = null,
        ownerReferencesUids: List<String> = listOf("owner-uid"),
        components: List<Map<String, Any>> = listOf(mapOf("volume" to mapOf("name" to "idea-server")))
    ): DevWorkspaceTemplate {
        val metadata = DevWorkspaceTemplateMetadata(name, namespace, pluginRegistryUrl, ownerReferencesUids)
        return DevWorkspaceTemplate(metadata, DevWorkspaceTemplateSpec(components))
    }
}
