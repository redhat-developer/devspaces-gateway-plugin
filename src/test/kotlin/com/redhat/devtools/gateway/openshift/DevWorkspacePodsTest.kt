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

import io.kubernetes.client.PortForward
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ListMeta
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

class DevWorkspacePodsTest {

    private val serverData = "from server"

    private lateinit var client: ApiClient
    private lateinit var pods: DevWorkspacePods

    private lateinit var buffer: ByteArray

    private val pod = V1Pod().apply {
       metadata = V1ObjectMeta().apply {
            name = "luke-skywalker"
        }
    }

    private val remotePort = 8080
    private var localPort = 0

    @BeforeEach
    fun beforeEach() {
        client = mockk(relaxed = true)
        pods = DevWorkspacePods(client)
        localPort = findFreePort()
        buffer = ByteArray(1024)
        mockkConstructor(PortForward::class)
    }

    @AfterEach
    fun afterEach() {
        unmockkConstructor(PortForward::class)
    }
    
    @Test
    fun `#forward copies from server to client`() {
        // given
        val portForwardResult = mockk<PortForward.PortForwardResult>(relaxed = true)
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } returns portForwardResult
        val serverIn = ByteArrayInputStream(serverData.toByteArray())
        every {
            portForwardResult.getInputStream(remotePort)
        } returns serverIn
        val serverOut = ByteArrayOutputStream()
        every {
            portForwardResult.getOutboundStream(remotePort)
        } returns serverOut

        // when
        val closeable = pods.forward(pod, localPort, remotePort)

        // then
        // wait for the server to start
        runBlocking { delay(100) }

        try {
            // Verify that data from server input stream is received by client
            val bytesRead = sendClientData("ping") // Send data to trigger server response
            assertThat(String(buffer, 0, bytesRead)).isEqualTo(serverData)
        } finally {
            closeable.close()
        }
    }

    @Test
    fun `#forward tries several times if connecting fails`() {
        // given
        every {
            anyConstructed<PortForward>().forward(pod, listOf(remotePort))
        } throws mockk<IOException>(relaxed = true)

        // when
        val closeable = pods.forward(pod, localPort, remotePort)

        // then
        // wait for the server to start
        runBlocking { delay(100) }
        Socket("127.0.0.1", localPort).apply {
            close() // trigger retry
        }
        runBlocking { delay(6000) } // 5 attempts * 1 second

        try {
            verify(atLeast = 2) { // 2+ retries
                anyConstructed<PortForward>().forward(pod, listOf(remotePort))
            }
        } finally {
            closeable.close()
        }
    }

    private fun sendClientData(data: String): Int {
        Socket("127.0.0.1", localPort).use {
            // client to server
            runCatching {
                it.outputStream.write(data.toByteArray())
                it.outputStream.flush()
            }

            // server to client
            return it.inputStream.read(buffer)
        }
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private fun createPodList(vararg podNames: String): V1PodList {
        return V1PodList().apply {
            metadata = V1ListMeta()
            items = podNames.map { name ->
                V1Pod().apply {
                    metadata = V1ObjectMeta().apply {
                        this.name = name
                    }
                }
            }
        }
    }

    @Test
    fun `#waitForPodsDeleted returns true when pods are already deleted`() = runBlocking {
        // given
        val namespace = "test-namespace"
        val workspaceName = "test-workspace"
        val timeout = 5L

        mockkConstructor(CoreV1Api::class)
        val emptyPodList = createPodList()

        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(namespace)
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } returns emptyPodList
            }
        }

        // when
        val result = pods.waitForPodsDeleted(namespace, workspaceName, timeout)

        // then
        assertThat(result).isTrue()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#waitForPodsDeleted returns true when pods get deleted during waiting`() = runBlocking {
        // given
        val namespace = "test-namespace"
        val workspaceName = "test-workspace"
        val timeout = 10L

        mockkConstructor(CoreV1Api::class)
        val podWithItems = createPodList("pod-1")
        val emptyPodList = createPodList()

        var callCount = 0
        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(namespace)
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } answers {
                    callCount++
                    if (callCount <= 2) podWithItems else emptyPodList
                }
            }
        }

        // when
        val result = pods.waitForPodsDeleted(namespace, workspaceName, timeout)

        // then
        assertThat(result).isTrue()
        assertThat(callCount).isGreaterThan(2)
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#waitForPodsDeleted returns false when timeout is reached`() = runBlocking {
        // given
        val namespace = "test-namespace"
        val workspaceName = "test-workspace"
        val timeout = 2L // short timeout

        mockkConstructor(CoreV1Api::class)
        val podWithItems = createPodList("pod-1")

        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(namespace)
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } returns podWithItems
            }
        }

        // when
        val result = pods.waitForPodsDeleted(namespace, workspaceName, timeout)

        // then
        assertThat(result).isFalse()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#waitForPodsDeleted uses correct label selector`() = runBlocking {
        // given
        val namespace = "test-namespace"
        val workspaceName = "my-workspace"
        val timeout = 5L

        mockkConstructor(CoreV1Api::class)
        val labelSelectorSlot = slot<String>()
        val emptyPodList = createPodList()

        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(namespace)
        } returns mockk {
            every { labelSelector(capture(labelSelectorSlot)) } returns mockk {
                every { execute() } returns emptyPodList
            }
        }

        // when
        pods.waitForPodsDeleted(namespace, workspaceName, timeout)

        // then
        assertThat(labelSelectorSlot.captured).isEqualTo("controller.devfile.io/devworkspace_name=my-workspace")
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#waitForPodsDeleted throws CancellationException when cancelled via callback`() = runBlocking {
        // given
        val namespace = "test-namespace"
        val workspaceName = "test-workspace"
        val timeout = 10L
        var cancelled = false

        mockkConstructor(CoreV1Api::class)
        val podWithItems = createPodList("pod-1")

        var callCount = 0
        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(namespace)
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } answers {
                    callCount++
                    if (callCount == 2) {
                        cancelled = true
                    }
                    podWithItems
                }
            }
        }

        // when/then
        assertThatThrownBy {
            runBlocking {
                pods.waitForPodsDeleted(namespace, workspaceName, timeout) {
                    if (cancelled) throw CancellationException("Test cancellation")
                }
            }
        }.isInstanceOf(CancellationException::class.java)

        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#waitForPodsDeleted retries when API exception occurs`() = runBlocking {
        // given
        val namespace = "test-namespace"
        val workspaceName = "test-workspace"
        val timeout = 10L

        mockkConstructor(CoreV1Api::class)
        val emptyPodList = createPodList()

        var callCount = 0
        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(namespace)
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } answers {
                    callCount++
                    if (callCount == 1) {
                        throw ApiException("Temporary API error")
                    }
                    emptyPodList
                }
            }
        }

        // when
        val result = pods.waitForPodsDeleted(namespace, workspaceName, timeout)

        // then
        assertThat(result).isTrue()
        assertThat(callCount).isEqualTo(2) // Failed once, then succeeded
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#waitForPodsDeleted continues polling after API errors`() = runBlocking {
        // given
        val namespace = "test-namespace"
        val workspaceName = "test-workspace"
        val timeout = 10L

        mockkConstructor(CoreV1Api::class)
        val podWithItems = createPodList("pod-1")
        val emptyPodList = createPodList()

        var callCount = 0
        every {
            anyConstructed<CoreV1Api>().listNamespacedPod(namespace)
        } returns mockk {
            every { labelSelector(any()) } returns mockk {
                every { execute() } answers {
                    callCount++
                    when (callCount) {
                        1 -> podWithItems
                        2 -> throw ApiException("Temporary error")
                        else -> emptyPodList
                    }
                }
            }
        }

        // when
        val result = pods.waitForPodsDeleted(namespace, workspaceName, timeout)

        // then
        assertThat(result).isTrue()
        assertThat(callCount).isGreaterThanOrEqualTo(3) // Had pods, error, then success
        unmockkConstructor(CoreV1Api::class)
    }
}