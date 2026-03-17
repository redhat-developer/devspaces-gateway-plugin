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

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CustomObjectsApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DevWorkspacePatchTest {

    private lateinit var devWorkspaces: DevWorkspaces
    private lateinit var customApi: CustomObjectsApi
    private lateinit var apiClient: ApiClient
    private lateinit var annotation: DevWorkspacePatch
    private lateinit var mockDevWorkspace: DevWorkspace

    private val namespace = "test-namespace"
    private val workspaceName = "test-workspace"

    @BeforeEach
    fun beforeEach() {
        apiClient = mockk(relaxed = true)
        devWorkspaces = mockk(relaxed = true)
        customApi = mockk(relaxed = true)
        mockDevWorkspace = mockk(relaxed = true)
        every { customApi.apiClient } returns apiClient
        every { mockDevWorkspace.annotations } returns emptyMap()
        annotation = DevWorkspacePatch(
            namespace,
            workspaceName,
            customApi
        ) { mockDevWorkspace }
    }

    @Test
    fun `#setRestartAnnotation calls patch API with correct add operation`() {
        // given
        val patchSlot = slot<Any>()
        val callBuilder = mockk<okhttp3.Call>(relaxed = true)
        every {
            customApi.patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                capture(patchSlot)
            )
        } returns mockk {
            every { buildCall(null) } returns callBuilder
        }

        // when
        annotation.setRestartAnnotation()

        // then
        verify {
            customApi.patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                any()
            )
        }
        @Suppress("UNCHECKED_CAST")
        val patch = patchSlot.captured as Array<Map<String, String>>
        assertThat(patch).hasSize(1)
        assertThat(patch[0]["op"]).isEqualTo("add")
        assertThat(patch[0]["path"]).isEqualTo("/metadata/annotations/che.eclipse.org~1restart-in-progress")
        assertThat(patch[0]["value"]).isEqualTo("true")
    }

    @Test
    fun `#setRestartAnnotation escapes forward slash in annotation key path`() {
        // given
        val patchSlot = slot<Any>()
        val callBuilder = mockk<okhttp3.Call>(relaxed = true)
        every {
            customApi.patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                capture(patchSlot)
            )
        } returns mockk {
            every { buildCall(null) } returns callBuilder
        }

        // when
        annotation.setRestartAnnotation()

        // then
        @Suppress("UNCHECKED_CAST")
        val patch = patchSlot.captured as Array<Map<String, String>>
        assertThat(patch[0]["path"]).contains("~1")
        assertThat(patch[0]["path"]).doesNotContain("/restart-in-progress")
    }

    @Test
    fun `#setRestartAnnotation throws ApiException when patch fails`() {
        // given
        every {
            customApi.patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                any()
            )
        } throws ApiException("Patch failed")

        // when/then
        assertThatThrownBy {
            annotation.setRestartAnnotation()
        }.isInstanceOf(ApiException::class.java)
            .hasMessageContaining("Patch failed")
    }

    @Test
    fun `#removeRestartAnnotation calls patch API with correct remove operation`() {
        // given
        val patchSlot = slot<Any>()
        val callBuilder = mockk<okhttp3.Call>(relaxed = true)
        every {
            customApi.patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                capture(patchSlot)
            )
        } returns mockk {
            every { buildCall(null) } returns callBuilder
        }

        // when
        annotation.removeRestartAnnotation()

        // then
        verify {
            customApi.patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                any()
            )
        }
        @Suppress("UNCHECKED_CAST")
        val patch = patchSlot.captured as Array<Map<String, String>>
        assertThat(patch).hasSize(1)
        assertThat(patch[0]["op"]).isEqualTo("remove")
        assertThat(patch[0]["path"]).isEqualTo("/metadata/annotations/che.eclipse.org~1restart-in-progress")
        assertThat(patch[0]).doesNotContainKey("value")
    }

    @Test
    fun `#removeRestartAnnotation escapes forward slash in annotation key path`() {
        // given
        val patchSlot = slot<Any>()
        val callBuilder = mockk<okhttp3.Call>(relaxed = true)
        every {
            customApi.patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                capture(patchSlot)
            )
        } returns mockk {
            every { buildCall(null) } returns callBuilder
        }

        // when
        annotation.removeRestartAnnotation()

        // then
        @Suppress("UNCHECKED_CAST")
        val patch = patchSlot.captured as Array<Map<String, String>>
        assertThat(patch[0]["path"]).contains("~1")
        assertThat(patch[0]["path"]).doesNotContain("/restart-in-progress")
    }

    @Test
    fun `#removeRestartAnnotation throws ApiException when patch fails`() {
        // given
        every {
            customApi.patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                any()
            )
        } throws ApiException("Remove failed")

        // when/then
        assertThatThrownBy {
            annotation.removeRestartAnnotation()
        }.isInstanceOf(ApiException::class.java)
            .hasMessageContaining("Remove failed")
    }

    @Test
    fun `#setSpecStarted with true calls patch API with replace operation and started=true`() {
        // given
        val patchSlot = slot<Any>()
        val callBuilder = mockk<okhttp3.Call>(relaxed = true)
        every {
            customApi.patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                capture(patchSlot)
            )
        } returns mockk {
            every { buildCall(null) } returns callBuilder
        }

        // when
        annotation.setSpecStarted(true)

        // then
        verify {
            customApi.patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                any()
            )
        }
        @Suppress("UNCHECKED_CAST")
        val patch = patchSlot.captured as Array<Map<String, Any>>
        assertThat(patch).hasSize(1)
        assertThat(patch[0]["op"]).isEqualTo("replace")
        assertThat(patch[0]["path"]).isEqualTo("/spec/started")
        assertThat(patch[0]["value"]).isEqualTo(true)
    }

    @Test
    fun `#setSpecStarted with false calls patch API with replace operation and started=false`() {
        // given
        val patchSlot = slot<Any>()
        val callBuilder = mockk<okhttp3.Call>(relaxed = true)
        every {
            customApi.patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                capture(patchSlot)
            )
        } returns mockk {
            every { buildCall(null) } returns callBuilder
        }

        // when
        annotation.setSpecStarted(false)

        // then
        verify {
            customApi.patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                any()
            )
        }
        @Suppress("UNCHECKED_CAST")
        val patch = patchSlot.captured as Array<Map<String, Any>>
        assertThat(patch).hasSize(1)
        assertThat(patch[0]["op"]).isEqualTo("replace")
        assertThat(patch[0]["path"]).isEqualTo("/spec/started")
        assertThat(patch[0]["value"]).isEqualTo(false)
    }

    @Test
    fun `#setSpecStarted throws ApiException when patch fails`() {
        // given
        every {
            customApi.patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                any()
            )
        } throws ApiException("Patch failed")

        // when/then
        assertThatThrownBy {
            annotation.setSpecStarted(true)
        }.isInstanceOf(ApiException::class.java)
            .hasMessageContaining("Patch failed")
    }

    private fun createDevWorkspace(
        name: String = workspaceName,
        namespace: String = this.namespace,
        annotations: Map<String, String> = emptyMap()
    ): DevWorkspace {
        val metadata = DevWorkspaceObjectMeta(
            name = name,
            namespace = namespace,
            uid = "test-uid",
            cheEditor = null,
            annotations = annotations
        )
        val spec = DevWorkspaceSpec(started = true)
        val status = DevWorkspaceStatus(phase = "Running")
        return DevWorkspace(metadata, spec, status)
    }

    @Test
    fun `#hasRestartAnnotation returns true when annotation is present and set to true`() {
        // given
        val annotations = mapOf(DevWorkspacePatch.RESTART_KEY to DevWorkspacePatch.RESTART_VALUE)
        every { mockDevWorkspace.annotations } returns annotations

        // when
        val result = annotation.hasRestartAnnotation()

        // then
        assertThat(result).isTrue()
    }

    @Test
    fun `#hasRestartAnnotation returns false when annotation is missing`() {
        // given
        every { mockDevWorkspace.annotations } returns emptyMap()

        // when
        val result = annotation.hasRestartAnnotation()

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `#hasRestartAnnotation returns false when annotation has wrong value`() {
        // given
        val annotations = mapOf(DevWorkspacePatch.RESTART_KEY to "false")
        every { mockDevWorkspace.annotations } returns annotations

        // when
        val result = annotation.hasRestartAnnotation()

        // then
        assertThat(result).isFalse()
    }
}
