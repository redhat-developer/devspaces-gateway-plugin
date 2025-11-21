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
package com.redhat.devtools.gateway.openshift

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class UtilsTest {

    @Test
    fun `#getValue returns value from nested map`() {
        // given
        val map = mapOf("jedis" to mapOf("republic" to "obiwan"))

        // when
        val value = Utils.getValue(map, arrayOf("jedis", "republic"))

        // then
        assertThat(value).isEqualTo("obiwan")
    }

    @Test
    fun `#getValue returns null if path does not exist`() {
        // given
        val map = mapOf("jedis" to mapOf("republic" to "obiwan"))

        // when
        val value = Utils.getValue(map, arrayOf("jedis", "empire"))

        // then
        assertThat(value).isNull()
    }

    @Test
    fun `#setValue sets value in nested map`() {
        // given
        val map = mutableMapOf("jedis" to mutableMapOf<String, Any>("republic" to "obiwan"))

        // when
        Utils.setValue(map, "yoda", arrayOf("jedis", "republic"))

        // then
        assertThat(map["jedis"] as Map<String, *>).containsEntry("republic", "yoda")
    }

    @Test
    fun `#setValue creates nested map if not exist`() {
        // given
        val map = mutableMapOf<String, Any>()

        // when
        Utils.setValue(map, "vader", arrayOf("sith", "empire"))

        // then
        assertThat(map["sith"] as Map<String, *>).containsEntry("empire", "vader")
    }

    @Test
    fun `#getValue returns null when intermediate map is null`() {
        // given
        val map = mapOf("jedis" to null)

        // when
        val value = Utils.getValue(map, arrayOf("jedis", "republic"))

        // then
        assertThat(value).isNull()
    }

    @Test
    fun `#setValue handles empty path correctly`() {
        // given
        val map = mutableMapOf<String, Any>()

        // when
        Utils.setValue(map, "luke", emptyArray())

        // then
        // Expect no change as an empty path is not valid for setting a value in a nested map structure
        assertThat(map).isEmpty()
    }

    @Test
    fun `#setValue handles path with single element correctly`() {
        // given
        val map = mutableMapOf<String, Any>()

        // when
        Utils.setValue(map, "han", arrayOf("smuggler"))

        // then
        assertThat(map).containsEntry("smuggler", "han")
    }

    @Test
    fun `#getValue handles empty object`() {
        // given
        val map = emptyMap<String, Any>()

        // when
        val value = Utils.getValue(map, arrayOf("jedis"))

        // then
        assertThat(value).isNull()
    }

    @Test
    fun `#getValue handles value of different type`() {
        // given
        val map = mapOf("jedis" to 123)

        // when
        val value = Utils.getValue(map, arrayOf("jedis", "republic"))

        // then
        assertThat(value).isNull()
    }
}
