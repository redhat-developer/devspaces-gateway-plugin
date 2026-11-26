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
package com.redhat.devtools.gateway.openshift

import com.redhat.devtools.gateway.testutil.podWith
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.OffsetDateTime

/** Pod liveness as the retry policy sees it: running, gone, or unknown. */
class PodLivenessProbeTest {

    private lateinit var client: ApiClient

    @BeforeEach
    fun beforeEach() {
        client = mockk(relaxed = true)
        mockkConstructor(CoreV1Api::class)
    }

    @AfterEach
    fun afterEach() {
        unmockkAll()
    }

    @Test
    fun `#check returns GONE when the pod is not found`(): Unit = runBlocking {
        // given
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("missing-pod", "test-ns")
        } returns mockk { every { execute() } throws ApiException(404, "Not Found") }

        // when
        val liveness = PodLivenessProbe(client).query(podWith(name = "missing-pod", namespace = "test-ns"))

        // then
        assertThat(liveness).isEqualTo(PodLiveness.GONE)
    }

    @Test
    fun `#check returns GONE when a running pod is terminating`(): Unit = runBlocking {
        // given
        val terminating = V1Pod().apply {
            metadata = V1ObjectMeta().apply { deletionTimestamp = OffsetDateTime.now() }
            status = V1PodStatus().apply { phase = "Running" }
        }
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("terminating-pod", "test-ns")
        } returns mockk { every { execute() } returns terminating }

        // when
        val liveness = PodLivenessProbe(client).query(podWith(name = "terminating-pod", namespace = "test-ns"))

        // then
        assertThat(liveness).isEqualTo(PodLiveness.GONE)
    }

    @Test
    fun `#check returns GONE when a pod has a terminal phase`(): Unit = runBlocking {
        // given - Failed/Succeeded pods will not serve the forward anymore
        val failed = V1Pod().apply {
            status = V1PodStatus().apply { phase = "Failed" }
        }
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("failed-pod", "test-ns")
        } returns mockk { every { execute() } returns failed }

        // when
        val liveness = PodLivenessProbe(client).query(podWith(name = "failed-pod", namespace = "test-ns"))

        // then
        assertThat(liveness).isEqualTo(PodLiveness.GONE)
    }

    @Test
    fun `#check returns UNKNOWN when the API call fails`(): Unit = runBlocking {
        // given
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("unreachable-pod", "test-ns")
        } returns mockk { every { execute() } throws IOException("connection refused") }

        // when
        val liveness = PodLivenessProbe(client).query(podWith(name = "unreachable-pod", namespace = "test-ns"))

        // then
        assertThat(liveness).isEqualTo(PodLiveness.UNKNOWN)
    }

    @Test
    fun `#check returns UNKNOWN when a pod is pending`(): Unit = runBlocking {
        // given - during a pod recreate race the same pod name can briefly be Pending
        val pending = V1Pod().apply {
            status = V1PodStatus().apply { phase = "Pending" }
        }
        every {
            anyConstructed<CoreV1Api>().readNamespacedPod("pending-pod", "test-ns")
        } returns mockk { every { execute() } returns pending }

        // when
        val liveness = PodLivenessProbe(client).query(podWith(name = "pending-pod", namespace = "test-ns"))

        // then
        assertThat(liveness).isEqualTo(PodLiveness.UNKNOWN)
    }
}
