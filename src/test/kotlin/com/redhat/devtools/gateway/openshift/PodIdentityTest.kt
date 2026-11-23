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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PodIdentityTest {

    @Test
    fun `pods with the same UID are the same pod even when the name differs`() {
        // given
        val first = podWith("old-generation", uid = "generation-1", namespace = "luke")
        val second = podWith("new-generation", uid = "generation-1", namespace = "luke")

        // when
        val same = isSamePod(first, second)

        // then
        assertThat(same).isTrue()
    }

    @Test
    fun `pods with different UIDs are not the same pod even when namespace and name match`() {
        // given
        val first = podWith("r2-d2", uid = "generation-1", namespace = "luke")
        val second = podWith("r2-d2", uid = "generation-2", namespace = "luke")

        // when
        val same = isSamePod(first, second)

        // then
        assertThat(same).isFalse()
    }

    @Test
    fun `pods without a UID fall back to namespace and name`() {
        // given
        val first = podWith("r2-d2", namespace = "luke")
        val second = podWith("r2-d2", namespace = "luke")

        // when
        val same = isSamePod(first, second)

        // then
        assertThat(same).isTrue()
    }

    @Test
    fun `pods without a UID in different namespaces are not the same pod`() {
        // given
        val first = podWith("r2-d2", namespace = "luke")
        val second = podWith("r2-d2", namespace = "han")

        // when
        val same = isSamePod(first, second)

        // then
        assertThat(same).isFalse()
    }

    @Test
    fun `pods without a UID and with different names are not the same pod`() {
        // given
        val first = podWith("r2-d2", namespace = "luke")
        val second = podWith("c-3po", namespace = "luke")

        // when
        val same = isSamePod(first, second)

        // then
        assertThat(same).isFalse()
    }

    @Test
    fun `a pod with a UID and one without fall back to namespace and name`() {
        // given
        val withUid = podWith("r2-d2", uid = "generation-1", namespace = "luke")
        val withoutUid = podWith("r2-d2", namespace = "luke")

        // when
        val same = isSamePod(withUid, withoutUid)

        // then
        assertThat(same).isTrue()
    }

    @Test
    fun `describePod names the pod and its generation`() {
        // given
        val pod = podWith("r2-d2", uid = "generation-1")

        // when
        val description = describePod(pod)

        // then
        assertThat(description).isEqualTo("name=r2-d2, uid=generation-1")
    }
}
