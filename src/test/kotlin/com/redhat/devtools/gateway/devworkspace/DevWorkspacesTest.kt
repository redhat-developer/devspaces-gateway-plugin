/*
 * Copyright (c) 2024-2025 Red Hat, Inc.
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
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DevWorkspacesTest {

    private lateinit var client: ApiClient
    private lateinit var customApi: CustomObjectsApi
    private lateinit var devWorkspaces: DevWorkspaces

    private val namespace = "test-namespace"
    private val workspaceName = "test-workspace"

    @BeforeEach
    fun beforeEach() {
        client = mockk(relaxed = true)
        customApi = mockk(relaxed = true)

        // Mock CustomObjectsApi constructor
        mockkConstructor(CustomObjectsApi::class)
        every { anyConstructed<CustomObjectsApi>().apiClient } returns client

        devWorkspaces = DevWorkspaces(client)
    }

    @AfterEach
    fun afterEach() {
        unmockkConstructor(CustomObjectsApi::class)
    }

    @Test
    fun `#start calls get and patches spec-started to true`() {
        // given
        val mockDevWorkspace = createMockDevWorkspace(namespace, workspaceName, false)
        val callBuilder = mockk<okhttp3.Call>(relaxed = true)

        mockGetDevWorkspace(mockDevWorkspace)
        mockPatchDevWorkspace(callBuilder)

        // when
        devWorkspaces.start(namespace, workspaceName)

        // then
        verifyPatchDevWorkspace()
    }

    @Test
    fun `#stop calls get and patches spec-started to false`() {
        // given
        val mockDevWorkspace = createMockDevWorkspace(namespace, workspaceName, true)
        val callBuilder = mockk<okhttp3.Call>(relaxed = true)

        mockGetDevWorkspace(mockDevWorkspace)
        mockPatchDevWorkspace(callBuilder)

        // when
        devWorkspaces.stop(namespace, workspaceName)

        // then
        verifyPatchDevWorkspace()
    }

    @Test
    fun `#isRestarting returns true when restart annotation is present`() {
        // given
        val mockDevWorkspace = createMockDevWorkspace(
            namespace,
            workspaceName,
            true,
            mapOf(DevWorkspacePatch.RESTART_KEY to DevWorkspacePatch.RESTART_VALUE)
        )

        mockGetDevWorkspace(mockDevWorkspace)

        // when
        val result = devWorkspaces.isRestarting(namespace, workspaceName)

        // then
        assert(result)
    }

    @Test
    fun `#isRestarting returns false when restart annotation is missing`() {
        // given
        val mockDevWorkspace = createMockDevWorkspace(namespace, workspaceName, true, emptyMap())

        mockGetDevWorkspace(mockDevWorkspace)

        // when
        val result = devWorkspaces.isRestarting(namespace, workspaceName)

        // then
        assert(!result)
    }

    @Test
    fun `#isRestarting returns false when restart annotation has wrong value`() {
        // given
        val mockDevWorkspace = createMockDevWorkspace(
            namespace,
            workspaceName,
            true,
            mapOf(DevWorkspacePatch.RESTART_KEY to "false")
        )

        mockGetDevWorkspace(mockDevWorkspace)

        // when
        val result = devWorkspaces.isRestarting(namespace, workspaceName)

        // then
        assert(!result)
    }

    @Test
    fun `#removeRestarting removes restart annotation`() {
        // given
        val mockDevWorkspace = createMockDevWorkspace(
            namespace,
            workspaceName,
            true,
            mapOf(DevWorkspacePatch.RESTART_KEY to DevWorkspacePatch.RESTART_VALUE)
        )
        val callBuilder = mockk<okhttp3.Call>(relaxed = true)

        mockGetDevWorkspace(mockDevWorkspace)
        mockPatchDevWorkspace(callBuilder)

        // when
        devWorkspaces.removeRestarting(namespace, workspaceName)

        // then
        verifyPatchDevWorkspace()
    }

    @Test
    fun `#start throws ApiException when API call fails`() {
        // given
        mockPatchDevWorkspaceThrows(ApiException("API error"))

        // when/then
        assertThatThrownBy {
            devWorkspaces.start(namespace, workspaceName)
        }.isInstanceOf(ApiException::class.java)
            .hasMessageContaining("API error")
    }

    @Test
    fun `#stop throws ApiException when API call fails`() {
        // given
        mockPatchDevWorkspaceThrows(ApiException("API error"))

        // when/then
        assertThatThrownBy {
            devWorkspaces.stop(namespace, workspaceName)
        }.isInstanceOf(ApiException::class.java)
            .hasMessageContaining("API error")
    }

    @Test
    fun `#isRestarting throws ApiException when API call fails`() {
        // given
        mockGetDevWorkspaceThrows(ApiException("API error"))

        // when/then
        assertThatThrownBy {
            devWorkspaces.isRestarting(namespace, workspaceName)
        }.isInstanceOf(ApiException::class.java)
            .hasMessageContaining("API error")
    }

    @Test
    fun `#removeRestarting throws ApiException when API call fails`() {
        // given
        mockPatchDevWorkspaceThrows(ApiException("API error"))

        // when/then
        assertThatThrownBy {
            devWorkspaces.removeRestarting(namespace, workspaceName)
        }.isInstanceOf(ApiException::class.java)
            .hasMessageContaining("API error")
    }

    @Test
    fun `#isIdeaEditorBased returns true for full path editor annotation`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-idea-server/latest")
        assert(devWorkspaces.isIdeaEditorBased(dw, emptyMap()))
    }

    @Test
    fun `#isIdeaEditorBased returns true for editor name only`() {
        val dw = createDevWorkspaceWithEditor("che-idea-server")
        assert(devWorkspaces.isIdeaEditorBased(dw, emptyMap()))
    }

    @Test
    fun `#isIdeaEditorBased returns true for editor name with version`() {
        val dw = createDevWorkspaceWithEditor("che-idea-server/latest")
        assert(devWorkspaces.isIdeaEditorBased(dw, emptyMap()))
    }

    @Test
    fun `#isIdeaEditorBased returns true for editor name with prefix`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-idea-server")
        assert(devWorkspaces.isIdeaEditorBased(dw, emptyMap()))
    }

    @Test
    fun `#isIdeaEditorBased returns false for non-idea editor`() {
        val dw = createDevWorkspaceWithEditor("eclipse/che-code/latest")
        assert(!devWorkspaces.isIdeaEditorBased(dw, emptyMap()))
    }

    @Test
    fun `#isIdeaEditorBased returns false for unknown editor`() {
        val dw = createDevWorkspaceWithEditor("unknown")
        assert(!devWorkspaces.isIdeaEditorBased(dw, emptyMap()))
    }

    @Test
    fun `#list includes non-Idea workspaces`() {
        // given
        mockListDevWorkspaces(
            listOf(
                createDevWorkspaceItem("idea-workspace", "eclipse/che-idea-server/latest"),
                createDevWorkspaceItem("code-workspace", "eclipse/che-code/latest")
            )
        )
        mockListDevWorkspaceTemplates(emptyList())

        // when
        val workspaces = devWorkspaces.list(namespace)

        // then
        assertThat(workspaces).hasSize(2)
        assertThat(workspaces.map { it.name })
            .containsExactlyInAnyOrder("idea-workspace", "code-workspace")
    }

    @Test
    fun `#listWithResult resolves labels for Idea and non-Idea workspaces`() {
        // given
        mockListDevWorkspaces(
            listOf(
                createDevWorkspaceItem("idea-workspace", "eclipse/che-idea-server/latest"),
                createDevWorkspaceItem("code-workspace", "eclipse/che-code/latest")
            )
        )
        mockListDevWorkspaceTemplates(emptyList())

        // when
        val result = devWorkspaces.listWithResult(namespace)

        // then
        assertThat(result.items).hasSize(2)
        assertThat(result.items.first { it.workspace.name == "idea-workspace" }.editorLabel)
            .isEqualTo("JetBrains")
        assertThat(result.items.first { it.workspace.name == "code-workspace" }.editorLabel)
            .isEqualTo("eclipse")
    }

    @Test
    fun `#listWithResult treats unauthorized templates list as unknown label without throwing`() {
        listWithResultTemplatesIgnored(ApiException(401, "Unauthorized"))
    }

    @Test
    fun `#listWithResult treats forbidden templates list as unknown label without throwing`() {
        listWithResultTemplatesIgnored(ApiException(403, "Forbidden"))
    }

    @Test
    fun `#listWithResult treats not-found templates list as unknown label without throwing`() {
        listWithResultTemplatesIgnored(ApiException(404, "Not Found"))
    }

    @Test
    fun `#listWithResult resolves template-based JetBrains workspace`() {
        // given — no che-editor annotation, but a template with an idea-server volume
        mockListDevWorkspaces(listOf(createDevWorkspaceItem("template-workspace", null)))
        mockListDevWorkspaceTemplates(
            listOf(
                mapOf(
                    "metadata" to mapOf(
                        "name" to "template-workspace-template",
                        "namespace" to namespace,
                        "ownerReferences" to listOf(
                            mapOf(
                                "apiVersion" to "workspace.devfile.io/v1alpha2",
                                "kind" to "DevWorkspace",
                                "uid" to "test-uid"
                            )
                        )
                    ),
                    "spec" to mapOf(
                        "components" to listOf(
                            mapOf("volume" to mapOf("name" to "idea-server"))
                        )
                    )
                )
            )
        )

        // when
        val result = devWorkspaces.listWithResult(namespace)

        // then
        assertThat(result.items).hasSize(1)
        assertThat(result.items[0].editorLabel).isEqualTo("JetBrains")
    }

    @Test
    fun `#listWithResult includes templateMap in result`() {
        // given
        mockListDevWorkspaces(listOf(createDevWorkspaceItem("template-workspace", null)))
        mockListDevWorkspaceTemplates(
            listOf(
                mapOf(
                    "metadata" to mapOf(
                        "name" to "template-workspace-template",
                        "namespace" to namespace,
                        "ownerReferences" to listOf(
                            mapOf(
                                "apiVersion" to "workspace.devfile.io/v1alpha2",
                                "kind" to "DevWorkspace",
                                "uid" to "test-uid"
                            )
                        )
                    ),
                    "spec" to mapOf(
                        "components" to listOf(
                            mapOf("volume" to mapOf("name" to "idea-server"))
                        )
                    )
                )
            )
        )

        // when
        val result = devWorkspaces.listWithResult(namespace)

        // then
        assertThat(result.templateMap).isNotEmpty
        assertThat(result.templateMap).containsKey("test-uid")
        assertThat(result.templateMap["test-uid"]).hasSize(1)
        assertThat(result.templatesUnavailable).isFalse()
    }

    @Test
    fun `#loadTemplateMap returns unavailable true for 401`() {
        // given
        mockListDevWorkspaceTemplatesThrows(ApiException(401, "Unauthorized"))

        // when
        val load = devWorkspaces.loadTemplateMap(namespace)

        // then
        assertThat(load.unavailable).isTrue()
        assertThat(load.map).isEmpty()
    }

    @Test
    fun `#loadTemplateMap returns unavailable true for 403`() {
        // given
        mockListDevWorkspaceTemplatesThrows(ApiException(403, "Forbidden"))

        // when
        val load = devWorkspaces.loadTemplateMap(namespace)

        // then
        assertThat(load.unavailable).isTrue()
        assertThat(load.map).isEmpty()
    }

    @Test
    fun `#loadTemplateMap returns unavailable true for 404`() {
        // given
        mockListDevWorkspaceTemplatesThrows(ApiException(404, "Not Found"))

        // when
        val load = devWorkspaces.loadTemplateMap(namespace)

        // then
        assertThat(load.unavailable).isTrue()
        assertThat(load.map).isEmpty()
    }

    @Test
    fun `#loadTemplateMap returns available with map on success`() {
        // given
        mockListDevWorkspaceTemplates(
            listOf(
                mapOf(
                    "metadata" to mapOf(
                        "name" to "template-workspace-template",
                        "namespace" to namespace,
                        "ownerReferences" to listOf(
                            mapOf(
                                "apiVersion" to "workspace.devfile.io/v1alpha2",
                                "kind" to "DevWorkspace",
                                "uid" to "test-uid"
                            )
                        )
                    ),
                    "spec" to mapOf(
                        "components" to listOf(
                            mapOf("volume" to mapOf("name" to "idea-server"))
                        )
                    )
                )
            )
        )

        // when
        val load = devWorkspaces.loadTemplateMap(namespace)

        // then
        assertThat(load.unavailable).isFalse()
        assertThat(load.map).isNotEmpty
        assertThat(load.map).containsKey("test-uid")
    }

    private fun listWithResultTemplatesIgnored(exception: ApiException) {
        // given
        mockListDevWorkspaces(listOf(createDevWorkspaceItem("plain-workspace", null)))
        mockListDevWorkspaceTemplatesThrows(exception)

        // when
        val result = devWorkspaces.listWithResult(namespace)

        // then
        assertThat(result.items).hasSize(1)
        assertThat(result.items[0].editorLabel).isEqualTo("Unknown")
        assertThat(result.templateMap).isEmpty()
        assertThat(result.templatesUnavailable).isTrue()
    }

    // Helper methods
    private fun mockGetDevWorkspace(devWorkspace: Any) {
        every {
            anyConstructed<CustomObjectsApi>().getNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName
            )
        } returns mockk {
            every { execute() } returns devWorkspace
        }
    }

    private fun mockGetDevWorkspaceThrows(exception: ApiException) {
        every {
            anyConstructed<CustomObjectsApi>().getNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName
            )
        } returns mockk {
            every { execute() } throws exception
        }
    }

    private fun mockListDevWorkspaces(items: List<Map<String, Any>>) {
        every {
            anyConstructed<CustomObjectsApi>().listNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces"
            )
        } returns mockk {
            every { execute() } returns mapOf(
                "metadata" to mapOf("resourceVersion" to "1"),
                "items" to items
            )
        }
    }

    private fun mockListDevWorkspaceTemplates(items: List<Map<String, Any>>) {
        every {
            anyConstructed<CustomObjectsApi>().listNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspacetemplates"
            )
        } returns mockk {
            every { execute() } returns mapOf("items" to items)
        }
    }

    private fun mockListDevWorkspaceTemplatesThrows(exception: ApiException) {
        every {
            anyConstructed<CustomObjectsApi>().listNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspacetemplates"
            )
        } returns mockk {
            every { execute() } throws exception
        }
    }

    private fun createDevWorkspaceItem(name: String, cheEditor: String?): Map<String, Any> {
        val annotations = if (cheEditor != null) {
            mapOf("che.eclipse.org/che-editor" to cheEditor)
        } else {
            emptyMap<String, String>()
        }
        return mapOf(
            "metadata" to mapOf(
                "name" to name,
                "namespace" to namespace,
                "uid" to "test-uid",
                "annotations" to annotations,
                "labels" to mapOf("kubernetes.io/metadata.name" to name)
            ),
            "spec" to mapOf(
                "started" to true
            ),
            "status" to mapOf(
                "phase" to "Running"
            )
        )
    }

    private fun mockPatchDevWorkspace(callBuilder: okhttp3.Call) {
        every {
            anyConstructed<CustomObjectsApi>().patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                any()
            )
        } returns mockk {
            every { buildCall(null) } returns callBuilder
        }
    }

    private fun mockPatchDevWorkspaceThrows(exception: ApiException) {
        every {
            anyConstructed<CustomObjectsApi>().patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                any()
            )
        } returns mockk {
            every { buildCall(null) } throws exception
        }
    }

    private fun verifyPatchDevWorkspace() {
        verify {
            anyConstructed<CustomObjectsApi>().patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                workspaceName,
                any()
            )
        }
    }

    private fun createDevWorkspaceWithEditor(cheEditor: String): DevWorkspace {
        return DevWorkspace(
            DevWorkspaceObjectMeta(
                name = "test-workspace",
                namespace = "test-namespace",
                uid = "test-uid",
                annotations = mapOf("che.eclipse.org/che-editor" to cheEditor),
                labels = emptyMap()
            ),
            DevWorkspaceSpec(started = true),
            DevWorkspaceStatus(phase = "Running")
        )
    }

    private fun createMockDevWorkspace(
        namespace: String,
        name: String,
        started: Boolean,
        annotations: Map<String, String> = emptyMap()
    ): Any {
        return mapOf(
            "metadata" to mapOf(
                "name" to name,
                "namespace" to namespace,
                "annotations" to annotations,
                "uid" to "test-uid"
            ),
            "spec" to mapOf(
                "started" to started
            ),
            "status" to mapOf(
                "phase" to "Running"
            )
        )
    }
}
