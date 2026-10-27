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
import io.kubernetes.client.openapi.models.V1PodCondition
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
    fun `#findFirstRunning returns null when no running and ready pod matches`() {
        // given — one pending pod, one running but not ready pod, one terminating pod
        mockkConstructor(CoreV1Api::class)
        val pending = podWith(name = "pending-pod", namespace = "test-ns", phase = "Pending")
        val notReady = podWith(name = "not-ready-pod", namespace = "test-ns", phase = "Running", isReady = false)
        val terminating = podWith(
            name = "terminating-pod",
            namespace = "test-ns",
            phase = "Running",
            isReady = true,
            deletionTimestamp = OffsetDateTime.now(),
        )
        val listRequest = mockk<CoreV1Api.APIlistNamespacedPodRequest>()
        every { listRequest.labelSelector(any()) } returns listRequest
        every { listRequest.execute() } returns V1PodList().apply { items = listOf(pending, notReady, terminating) }
        every { anyConstructed<CoreV1Api>().listNamespacedPod("test-ns") } returns listRequest

        // when
        val result = pods.findFirstRunning("test-ns", "controller.devfile.io/devworkspace_name=my-workspace")

        // then
        assertThat(result).isNull()
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#findFirstRunning returns the newest running and ready pod`() {
        // given — items are deliberately not sorted by creation timestamp
        mockkConstructor(CoreV1Api::class)
        val oldest = podWith(
            name = "oldest-pod",
            namespace = "test-ns",
            phase = "Running",
            isReady = true,
            creationTimestamp = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        )
        val newest = podWith(
            name = "newest-pod",
            namespace = "test-ns",
            phase = "Running",
            isReady = true,
            creationTimestamp = OffsetDateTime.parse("2026-01-03T00:00:00Z"),
        )
        val middle = podWith(
            name = "middle-pod",
            namespace = "test-ns",
            phase = "Running",
            isReady = true,
            creationTimestamp = OffsetDateTime.parse("2026-01-02T00:00:00Z"),
        )
        val listRequest = mockk<CoreV1Api.APIlistNamespacedPodRequest>()
        every { listRequest.labelSelector(any()) } returns listRequest
        every { listRequest.execute() } returns V1PodList().apply { items = listOf(oldest, newest, middle) }
        every { anyConstructed<CoreV1Api>().listNamespacedPod("test-ns") } returns listRequest

        // when
        val result = pods.findFirstRunning("test-ns", "controller.devfile.io/devworkspace_name=my-workspace")

        // then
        assertThat(result).isSameAs(newest)
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#findFirstRunning skips a terminating running pod`() {
        // given — the newest pod is terminating, so the newer non-terminating pod must win
        mockkConstructor(CoreV1Api::class)
        val oldPod = podWith(
            name = "old-pod",
            namespace = "test-ns",
            phase = "Running",
            isReady = true,
            creationTimestamp = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        )
        val newerPod = podWith(
            name = "newer-pod",
            namespace = "test-ns",
            phase = "Running",
            isReady = true,
            creationTimestamp = OffsetDateTime.parse("2026-01-02T00:00:00Z"),
        )
        val terminatingPod = podWith(
            name = "terminating-pod",
            namespace = "test-ns",
            phase = "Running",
            isReady = true,
            creationTimestamp = OffsetDateTime.parse("2026-01-03T00:00:00Z"),
            deletionTimestamp = OffsetDateTime.now(),
        )
        val listRequest = mockk<CoreV1Api.APIlistNamespacedPodRequest>()
        every { listRequest.labelSelector(any()) } returns listRequest
        every { listRequest.execute() } returns V1PodList().apply { items = listOf(oldPod, terminatingPod, newerPod) }
        every { anyConstructed<CoreV1Api>().listNamespacedPod("test-ns") } returns listRequest

        // when
        val result = pods.findFirstRunning("test-ns", "controller.devfile.io/devworkspace_name=my-workspace")

        // then
        assertThat(result).isSameAs(newerPod)
        unmockkConstructor(CoreV1Api::class)
    }

    @Test
    fun `#findFirstRunning skips a running pod that is not ready`() {
        // given — the not-ready pod is newer than the ready one
        mockkConstructor(CoreV1Api::class)
        val readyPod = podWith(
            name = "ready-pod",
            namespace = "test-ns",
            phase = "Running",
            isReady = true,
            creationTimestamp = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        )
        val notReadyPod = podWith(
            name = "not-ready-pod",
            namespace = "test-ns",
            phase = "Running",
            isReady = false,
            creationTimestamp = OffsetDateTime.parse("2026-01-02T00:00:00Z"),
        )
        val listRequest = mockk<CoreV1Api.APIlistNamespacedPodRequest>()
        every { listRequest.labelSelector(any()) } returns listRequest
        every { listRequest.execute() } returns V1PodList().apply { items = listOf(notReadyPod, readyPod) }
        every { anyConstructed<CoreV1Api>().listNamespacedPod("test-ns") } returns listRequest

        // when
        val result = pods.findFirstRunning("test-ns", "controller.devfile.io/devworkspace_name=my-workspace")

        // then
        assertThat(result).isSameAs(readyPod)
        unmockkConstructor(CoreV1Api::class)
    }

    private fun podWith(
        name: String,
        namespace: String,
        phase: String? = null,
        isReady: Boolean = false,
        creationTimestamp: OffsetDateTime? = null,
        deletionTimestamp: OffsetDateTime? = null,
    ): V1Pod {
        return V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                this.name = name
                this.namespace = namespace
                this.creationTimestamp = creationTimestamp
                this.deletionTimestamp = deletionTimestamp
            }
            status = phase?.let {
                V1PodStatus().apply {
                    this.phase = it
                    conditions = listOf(
                        V1PodCondition().apply {
                            type = "Ready"
                            status = if (isReady) "True" else "False"
                        }
                    )
                }
            }
        }
    }

}
