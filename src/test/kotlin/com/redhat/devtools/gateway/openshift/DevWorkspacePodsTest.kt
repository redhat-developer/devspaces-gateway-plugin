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

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.openapi.models.V1PodStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.unmockkConstructor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class DevWorkspacePodsTest {

    private lateinit var client: ApiClient
    private lateinit var pods: DevWorkspacePods

    @BeforeEach
    fun beforeEach() {
        client = mockk(relaxed = true)
        pods = DevWorkspacePods(client)
    }

    @AfterEach
    fun afterEach() {
        unmockkAll()
    }

    @Test
    fun `#isPodRunning returns true when pod phase is Running`() {
        // given
        val runningPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "running-pod"
                namespace = "test-ns"
            }
        }

        mockkConstructor(CoreV1Api::class)
        val response = V1Pod().apply {
            metadata = V1ObjectMeta() // no deletion timestamp: the pod is not terminating
            status = V1PodStatus().apply { phase = "Running" }
        }
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("running-pod", "test-ns")
        } returns mockk { every { execute() } returns response }

        // when
        val result = pods.isPodRunning(runningPod)

        // then
        assertThat(result).isTrue()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#isPodRunning returns false when a running pod is terminating`() {
        // given
        val terminatingPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "terminating-pod"
                namespace = "test-ns"
            }
        }

        mockkConstructor(CoreV1Api::class)
        val response = V1Pod().apply {
            metadata = V1ObjectMeta().apply { deletionTimestamp = OffsetDateTime.now() }
            status = V1PodStatus().apply { phase = "Running" }
        }
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("terminating-pod", "test-ns")
        } returns mockk { every { execute() } returns response }

        // when
        val result = pods.isPodRunning(terminatingPod)

        // then
        assertThat(result).isFalse()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#isPodRunning returns false when pod phase is Pending`() {
        // given
        val pendingPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "pending-pod"
                namespace = "test-ns"
            }
        }

        mockkConstructor(CoreV1Api::class)
        val response = mockk<V1Pod>(relaxed = true)
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("pending-pod", "test-ns")
        } returns mockk { every { execute() } returns response }
        every { response.status } returns V1PodStatus().apply { phase = "Pending" }

        // when
        val result = pods.isPodRunning(pendingPod)

        // then
        assertThat(result).isFalse()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#isPodRunning returns false when pod phase is Failed`() {
        // given
        val failedPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "failed-pod"
                namespace = "test-ns"
            }
        }

        mockkConstructor(CoreV1Api::class)
        val response = mockk<V1Pod>(relaxed = true)
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("failed-pod", "test-ns")
        } returns mockk { every { execute() } returns response }
        every { response.status } returns V1PodStatus().apply { phase = "Failed" }

        // when
        val result = pods.isPodRunning(failedPod)

        // then
        assertThat(result).isFalse()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#isPodRunning returns false when pod phase is Succeeded`() {
        // given
        val succeededPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "succeeded-pod"
                namespace = "test-ns"
            }
        }

        mockkConstructor(CoreV1Api::class)
        val response = mockk<V1Pod>(relaxed = true)
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("succeeded-pod", "test-ns")
        } returns mockk { every { execute() } returns response }
        every { response.status } returns V1PodStatus().apply { phase = "Succeeded" }

        // when
        val result = pods.isPodRunning(succeededPod)

        // then
        assertThat(result).isFalse()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#isPodRunning returns false when pod has no name`() {
        // given — pod with null name
        val noNamePod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                namespace = "test-ns"
            }
        }

        // when
        val result = pods.isPodRunning(noNamePod)

        // then — no API call needed
        assertThat(result).isFalse()
    }

    @Test
    fun `#isPodRunning returns false when pod has no namespace`() {
        // given — pod with null namespace
        val noNsPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "my-pod"
            }
        }

        // when
        val result = pods.isPodRunning(noNsPod)

        // then — no API call needed
        assertThat(result).isFalse()
    }

    @Test
    fun `#isPodRunning returns false when pod has no metadata`() {
        // given
        val noMetaPod = V1Pod()

        // when
        val result = pods.isPodRunning(noMetaPod)

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `#isPodRunning returns false when API call throws exception`() {
        // given
        val pod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "error-pod"
                namespace = "test-ns"
            }
        }

        mockkConstructor(CoreV1Api::class)
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("error-pod", "test-ns")
        } returns mockk { every { execute() } throws ApiException("connection refused") }

        // when
        val result = pods.isPodRunning(pod)

        // then
        assertThat(result).isFalse()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#findFirst returns null when no pod matches`() {
        // given
        mockkConstructor(CoreV1Api::class)
        val listRequest = mockk<CoreV1Api.APIlistNamespacedPodRequest>()
        every { listRequest.labelSelector(any()) } returns listRequest
        every { listRequest.execute() } returns V1PodList().apply { items = emptyList() }
        every { anyConstructed<CoreV1Api>().listNamespacedPod("test-ns") } returns listRequest

        // when
        val result = pods.findFirst("test-ns", "controller.devfile.io/devworkspace_name=no-workspace")

        // then
        assertThat(result).isNull()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#findFirst returns the first matching pod`() {
        // given
        mockkConstructor(CoreV1Api::class)
        val first = podWith(name = "first-pod", namespace = "test-ns")
        val second = podWith(name = "second-pod", namespace = "test-ns")
        val listRequest = mockk<CoreV1Api.APIlistNamespacedPodRequest>()
        every { listRequest.labelSelector(any()) } returns listRequest
        every { listRequest.execute() } returns V1PodList().apply { items = listOf(first, second) }
        every { anyConstructed<CoreV1Api>().listNamespacedPod("test-ns") } returns listRequest

        // when
        val result = pods.findFirst("test-ns", "controller.devfile.io/devworkspace_name=my-workspace")

        // then
        assertThat(result).isSameAs(first)
        unmockkConstructor(CoreV1Api::class)
    }

    private fun podWith(name: String, namespace: String): V1Pod {
        return V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                this.name = name
                this.namespace = namespace
            }
        }
    }

}
