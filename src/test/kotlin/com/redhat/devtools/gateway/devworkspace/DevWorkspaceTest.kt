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

class DevWorkspaceTest {

    @Test
    fun `labels property returns correct map`() {
        // given
        val labels = mapOf("env" to "dev", "team" to "backend")
        val devWorkspace = createDevWorkspace(labels = labels)

        // when
        val result = devWorkspace.labels

        // then
        assertThat(result).isEqualTo(labels)
        assertThat(result).containsEntry("env", "dev")
        assertThat(result).containsEntry("team", "backend")
    }

    @Test
    fun `labels property returns empty map when no labels`() {
        // given
        val devWorkspace = createDevWorkspace(labels = emptyMap())

        // when
        val result = devWorkspace.labels

        // then
        assertThat(result).isEmpty()
    }

    @Test
    fun `annotations property returns correct map`() {
        // given
        val annotations = mapOf(
            "che.eclipse.org/che-editor" to "che-idea/latest",
            "custom.annotation" to "value"
        )
        val devWorkspace = createDevWorkspace(annotations = annotations)

        // when
        val result = devWorkspace.annotations

        // then
        assertThat(result).isEqualTo(annotations)
        assertThat(result).containsEntry("che.eclipse.org/che-editor", "che-idea/latest")
        assertThat(result).containsEntry("custom.annotation", "value")
    }

    @Test
    fun `annotations property returns empty map when no annotations`() {
        // given
        val devWorkspace = createDevWorkspace(annotations = emptyMap())

        // when
        val result = devWorkspace.annotations

        // then
        assertThat(result).isEmpty()
    }

    @Test
    fun `equals returns true for workspaces with same name, namespace, annotations and labels`() {
        // given
        val annotations = mapOf("key1" to "value1")
        val labels = mapOf("label1" to "labelValue1")
        val workspace1 = createDevWorkspace(
            name = "test-workspace",
            namespace = "test-ns",
            annotations = annotations,
            labels = labels
        )
        val workspace2 = createDevWorkspace(
            name = "test-workspace",
            namespace = "test-ns",
            annotations = annotations,
            labels = labels
        )

        // when/then
        assertThat(workspace1).isEqualTo(workspace2)
    }

    @Test
    fun `equals returns false for workspaces with different annotations`() {
        // given
        val workspace1 = createDevWorkspace(
            name = "test-workspace",
            namespace = "test-ns",
            annotations = mapOf("key1" to "value1"),
            labels = emptyMap()
        )
        val workspace2 = createDevWorkspace(
            name = "test-workspace",
            namespace = "test-ns",
            annotations = mapOf("key1" to "value2"),
            labels = emptyMap()
        )

        // when/then
        assertThat(workspace1).isNotEqualTo(workspace2)
    }

    @Test
    fun `equals returns false for workspaces with different labels`() {
        // given
        val workspace1 = createDevWorkspace(
            name = "test-workspace",
            namespace = "test-ns",
            annotations = emptyMap(),
            labels = mapOf("env" to "dev")
        )
        val workspace2 = createDevWorkspace(
            name = "test-workspace",
            namespace = "test-ns",
            annotations = emptyMap(),
            labels = mapOf("env" to "prod")
        )

        // when/then
        assertThat(workspace1).isNotEqualTo(workspace2)
    }

    @Test
    fun `equals returns false for workspaces with different names`() {
        // given
        val workspace1 = createDevWorkspace(name = "workspace1")
        val workspace2 = createDevWorkspace(name = "workspace2")

        // when/then
        assertThat(workspace1).isNotEqualTo(workspace2)
    }

    @Test
    fun `equals returns false for workspaces with different namespaces`() {
        // given
        val workspace1 = createDevWorkspace(namespace = "namespace1")
        val workspace2 = createDevWorkspace(namespace = "namespace2")

        // when/then
        assertThat(workspace1).isNotEqualTo(workspace2)
    }

    @Test
    fun `cheEditor extension property extracts editor from annotations`() {
        // given
        val annotations = mapOf("che.eclipse.org/che-editor" to "che-idea/latest")
        val devWorkspace = createDevWorkspace(annotations = annotations)

        // when
        val result = devWorkspace.cheEditor

        // then
        assertThat(result).isEqualTo("che-idea/latest")
    }

    @Test
    fun `cheEditor extension property returns unknown when annotation is missing`() {
        // given
        val devWorkspace = createDevWorkspace(annotations = emptyMap())

        // when
        val result = devWorkspace.cheEditor

        // then
        assertThat(result).isEqualTo("unknown")
    }

    @Test
    fun `cheEditor extension property returns unknown when annotation is null`() {
        // given
        val devWorkspace = createDevWorkspace(annotations = mapOf("other.annotation" to "value"))

        // when
        val result = devWorkspace.cheEditor

        // then
        assertThat(result).isEqualTo("unknown")
    }

    @Test
    fun `DevWorkspaceObjectMeta from map creates object with labels and annotations`() {
        // given
        val map = mapOf(
            "name" to "test-workspace",
            "namespace" to "test-ns",
            "uid" to "test-uid",
            "annotations" to mapOf("anno-key" to "anno-value"),
            "labels" to mapOf("label-key" to "label-value")
        )

        // when
        val result = DevWorkspaceObjectMeta.from(map)

        // then
        assertThat(result.name).isEqualTo("test-workspace")
        assertThat(result.namespace).isEqualTo("test-ns")
        assertThat(result.uid).isEqualTo("test-uid")
        assertThat(result.annotations).containsEntry("anno-key", "anno-value")
        assertThat(result.labels).containsEntry("label-key", "label-value")
    }

    @Test
    fun `DevWorkspaceObjectMeta from map handles missing annotations and labels`() {
        // given
        val map = mapOf(
            "name" to "test-workspace",
            "namespace" to "test-ns",
            "uid" to "test-uid"
        )

        // when
        val result = DevWorkspaceObjectMeta.from(map)

        // then
        assertThat(result.annotations).isEmpty()
        assertThat(result.labels).isEmpty()
    }

    private fun createDevWorkspace(
        name: String = "test-workspace",
        namespace: String = "test-namespace",
        annotations: Map<String, String> = emptyMap(),
        labels: Map<String, String> = emptyMap()
    ): DevWorkspace {
        val metadata = DevWorkspaceObjectMeta(
            name = name,
            namespace = namespace,
            uid = "test-uid",
            annotations = annotations,
            labels = labels
        )
        val spec = DevWorkspaceSpec(started = true)
        val status = DevWorkspaceStatus(phase = "Running")
        return DevWorkspace(metadata, spec, status)
    }
}
