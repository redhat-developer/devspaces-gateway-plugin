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
