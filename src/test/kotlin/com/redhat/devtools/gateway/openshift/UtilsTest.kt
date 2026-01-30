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

    @Test
    fun `#mapOfNotNull returns map with all non-null values`() {
        // when
        val result = mapOfNotNull(
            "key1" to "value1",
            "key2" to "value2",
            "key3" to "value3"
        )

        // then
        assertThat(result).hasSize(3)
        assertThat(result).containsEntry("key1", "value1")
        assertThat(result).containsEntry("key2", "value2")
        assertThat(result).containsEntry("key3", "value3")
    }

    @Test
    fun `#mapOfNotNull filters out null values`() {
        // when
        val result = mapOfNotNull(
            "key1" to "value1",
            "key2" to null,
            "key3" to "value3",
            "key4" to null
        )

        // then
        assertThat(result).hasSize(2)
        assertThat(result).containsEntry("key1", "value1")
        assertThat(result).containsEntry("key3", "value3")
        assertThat(result).doesNotContainKey("key2")
        assertThat(result).doesNotContainKey("key4")
    }

    @Test
    fun `#mapOfNotNull returns empty map when all values are null`() {
        // when
        val result = mapOfNotNull(
            "key1" to null,
            "key2" to null,
            "key3" to null
        )

        // then
        assertThat(result).isEmpty()
    }

    @Test
    fun `#mapOfNotNull returns empty map when no pairs provided`() {
        // when
        val result = mapOfNotNull<String, String>()

        // then
        assertThat(result).isEmpty()
    }

    @Test
    fun `#mapOfNotNull works with different value types`() {
        // when
        val stringResult = mapOfNotNull("key1" to "value", "key2" to null)
        val intResult = mapOfNotNull("key1" to 42, "key2" to null, "key3" to 100)
        val booleanResult = mapOfNotNull("key1" to true, "key2" to null, "key3" to false)

        // then
        assertThat(stringResult).hasSize(1)
        assertThat(stringResult).containsEntry("key1", "value")

        assertThat(intResult).hasSize(2)
        assertThat(intResult).containsEntry("key1", 42)
        assertThat(intResult).containsEntry("key3", 100)

        assertThat(booleanResult).hasSize(2)
        assertThat(booleanResult).containsEntry("key1", true)
        assertThat(booleanResult).containsEntry("key3", false)
    }

    @Test
    fun `#mapOfNotNull preserves order of non-null entries`() {
        // when
        val result = mapOfNotNull(
            "key1" to "value1",
            "key2" to null,
            "key3" to "value3"
        )

        // then
        val entries = result.entries.toList()
        assertThat(entries).hasSize(2)
        assertThat(entries[0].key).isEqualTo("key1")
        assertThat(entries[1].key).isEqualTo("key3")
    }
}
