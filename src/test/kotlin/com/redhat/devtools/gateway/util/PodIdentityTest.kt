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
package com.redhat.devtools.gateway.util

import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class PodIdentityTest {

    @Test
    fun `returns name and uid when both are present`() {
        // given
        val pod = V1Pod().metadata(V1ObjectMeta().name("workspace-pod-abc123").uid("a1b2c3d4-e5f6-7890"))

        // when
        val result = podLogIdentity(pod)

        // then
        assertThat(result).isEqualTo("workspace-pod-abc123 (uid=a1b2c3d4-e5f6-7890)")
    }

    @Test
    fun `returns unknown name when metadata name is null`() {
        // given
        val pod = V1Pod().metadata(V1ObjectMeta().uid("a1b2c3d4-e5f6-7890"))

        // when
        val result = podLogIdentity(pod)

        // then
        assertThat(result).isEqualTo("unknown (uid=a1b2c3d4-e5f6-7890)")
    }

    @Test
    fun `returns unknown uid when metadata uid is null`() {
        // given
        val pod = V1Pod().metadata(V1ObjectMeta().name("workspace-pod-xyz789"))

        // when
        val result = podLogIdentity(pod)

        // then
        assertThat(result).isEqualTo("workspace-pod-xyz789 (uid=unknown)")
    }

    @Test
    fun `returns unknown for both when metadata is null`() {
        // given
        val pod = V1Pod()

        // when
        val result = podLogIdentity(pod)

        // then
        assertThat(result).isEqualTo("unknown (uid=unknown)")
    }

    @Test
    fun `includes terminating when deletionTimestamp is present`() {
        // given
        val pod = V1Pod().metadata(
            V1ObjectMeta()
                .name("terminating-pod")
                .uid("e5f6a7b8-c9d0-1234")
                .deletionTimestamp(OffsetDateTime.now())
        )

        // when
        val result = podLogIdentity(pod)

        // then
        assertThat(result).isEqualTo("terminating-pod (uid=e5f6a7b8-c9d0-1234, terminating)")
    }

    @Test
    fun `does not include terminating when deletionTimestamp is null`() {
        // given
        val pod = V1Pod().metadata(V1ObjectMeta().name("running-pod").uid("e5f6a7b8-c9d0-1234"))

        // when
        val result = podLogIdentity(pod)

        // then
        assertThat(result).isEqualTo("running-pod (uid=e5f6a7b8-c9d0-1234)")
    }

    @Test
    fun `podIdentity prefers uid`() {
        // given
        val pod = V1Pod().metadata(
            V1ObjectMeta().namespace("test-ns").name("workspace-pod").uid("uid-1")
        )

        // when
        val result = podIdentity(pod)

        // then
        assertThat(result).isEqualTo("uid-1")
    }

    @Test
    fun `podIdentity falls back to namespace and name when uid is null`() {
        // given
        val pod = V1Pod().metadata(V1ObjectMeta().namespace("test-ns").name("workspace-pod"))

        // when
        val result = podIdentity(pod)

        // then
        assertThat(result).isEqualTo("test-ns/workspace-pod")
    }

    @Test
    fun `podIdentity falls back to name when uid and namespace are null`() {
        // given
        val pod = V1Pod().metadata(V1ObjectMeta().name("workspace-pod"))

        // when
        val result = podIdentity(pod)

        // then
        assertThat(result).isEqualTo("workspace-pod")
    }

    @Test
    fun `podIdentity returns null when uid, namespace and name are null`() {
        // given
        val pod = V1Pod().metadata(V1ObjectMeta())

        // when
        val result = podIdentity(pod)

        // then
        assertThat(result).isNull()
    }
}
