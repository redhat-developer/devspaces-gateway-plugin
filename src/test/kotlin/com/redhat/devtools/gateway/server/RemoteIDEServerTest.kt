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
package com.redhat.devtools.gateway.server

import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.openshift.Pods
import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodSpec
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class RemoteIDEServerTest {

    private lateinit var devSpacesContext: DevSpacesContext
    private lateinit var remoteIDEServer: RemoteIDEServer

    @BeforeEach
    fun beforeEach() {
        devSpacesContext = mockk(relaxed = true)

        mockkConstructor(Pods::class)
        val mockPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "test-pod"
            }
            spec = V1PodSpec().apply {
                containers = listOf(
                    V1Container().apply {
                        name = "test-container"
                        ports = listOf(
                            mockk(relaxed = true) {
                                every { name } returns "idea-server"
                            }
                        )
                    }
                )
            }
        }
        coEvery {
            anyConstructed<Pods>().findFirst(any(), any())
        } returns mockPod

        remoteIDEServer = spyk(RemoteIDEServer(devSpacesContext), recordPrivateCalls = true)
    }

    @AfterEach
    fun afterEach() {
        unmockkAll()
    }

    @Test
    fun `#waitServerTerminated should return true if server terminated`() {
        // given
        val withProjects = RemoteIDEServerStatus(
            null,
            "",
            "",
            "",
            "",
            arrayOf(
                ProjectInfo("test", "test", "test", "test", "test")
            )
        )
        val withoutProjects = RemoteIDEServerStatus(
            null,
            "",
            "",
            "",
            "",
            emptyArray()
        )
        coEvery {
            remoteIDEServer.getStatus()
        } returns withProjects andThen withoutProjects

        // when
        val result = runBlocking {
            remoteIDEServer.waitServerTerminated()
        }

        // then
        assertThat(result).isTrue
    }

    @Test
    fun `#waitServerTerminated should return false on timeout`() {
        // given
        coEvery {
            remoteIDEServer.getStatus()
        } returns RemoteIDEServerStatus(
            "test", // Should not be 'null' for a running server
            "",
            "",
            "",
            "",
            arrayOf(
                ProjectInfo(
                    "test",
                    "test",
                    "test",
                    "test",
                    "test"
                )
            )
        )

        // when
        val result = runBlocking {
            remoteIDEServer.waitServerTerminated()
        }

        // then
        assertThat(result).isFalse
    }

    @Test
    fun `#waitServerTerminated should return false on exception`() {
        // given
        coEvery {
            remoteIDEServer.getStatus()
        } throws IOException("error")

        // when
        val result = runBlocking {
            remoteIDEServer.waitServerTerminated()
        }

        // then
        assertThat(result).isFalse
    }
}
