/*
 * Copyright (c) 2025 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is aavailable at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.kubeconfig

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BlockStyleFilePersisterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `save should write kubeconfig to file`() {
        // given
        val file = tempDir.resolve("test-kubeconfig").toFile()
        val persister = BlockStyleFilePersister(file)

        val expectedContent = """
            apiVersion: v1
            kind: Config
            current-context: death-star-context
            preferences: {}
            clusters:
            - name: death-star
              cluster:
                server: https://death-star.com
            contexts:
            - name: death-star-context
              context:
                cluster: death-star
                user: darth-vader
            users:
            - name: darth-vader
              user:
                token: join-the-dark-side
        """.trimIndent()

        val clusters = arrayListOf<Any?>(
            KubeConfigNamedCluster(
                KubeConfigCluster(
                    server = "https://death-star.com"
                ),
                name = "death-star"
            ).toMap()
        )
        val contexts = arrayListOf<Any?>(
            KubeConfigNamedContext(
                KubeConfigContext(
                    user = "darth-vader",
                    cluster = "death-star"
                ),
                name = "death-star-context"
            ).toMap()
        )
        val users = arrayListOf<Any?>(
            KubeConfigNamedUser(
                KubeConfigUser(
                    token = "join-the-dark-side"
                ),
                name = "darth-vader"
            ).toMap()
        )

        // when
        persister.save(
            contexts,
            clusters,
            users,
            emptyMap<String, Any>(),
            "death-star-context"
        )

        // then
        val actualContent = file.readText()
        assertYamlEquals(expectedContent, actualContent)
    }

    @Test
    fun `save should handle empty kubeconfig`() {
        // given
        val file = tempDir.resolve("empty-kubeconfig").toFile()
        val persister = BlockStyleFilePersister(file)

        val expectedContent = """
            apiVersion: v1
            kind: Config
            current-context: ""
            preferences: {}
            clusters: []
            contexts: []
            users: []
        """.trimIndent()

        // when
        persister.save(
            ArrayList(),
            ArrayList(),
            ArrayList(),
            mutableMapOf<String, Any>(),
            ""
        )

        // then
        val actualContent = file.readText()
        assertYamlEquals(expectedContent, actualContent)
    }

    @Test
    fun `save should handle multiple entries`() {
        // given
        val file = tempDir.resolve("multiple-entries-kubeconfig").toFile()
        val persister = BlockStyleFilePersister(file)

        val expectedContent = """
            apiVersion: v1
            kind: Config
            current-context: tatooine-context
            preferences: {}
            clusters:
            - name: tatooine
              cluster:
                server: https://tatooine.com
            - name: dagobah
              cluster:
                server: https://dagobah.com
            contexts:
            - name: tatooine-context
              context:
                cluster: tatooine
                user: luke-skywalker
            - name: dagobah-context
              context:
                cluster: dagobah
                user: yoda
            users:
            - name: luke-skywalker
              user:
                token: use-the-force
            - name: yoda
              user:
                token: do-or-do-not
        """.trimIndent()

        val clusters = arrayListOf<Any?>(
            KubeConfigNamedCluster(KubeConfigCluster(server = "https://tatooine.com"), name = "tatooine").toMap(),
            KubeConfigNamedCluster(KubeConfigCluster(server = "https://dagobah.com"), name = "dagobah").toMap()
        )
        val contexts = arrayListOf<Any?>(
            KubeConfigNamedContext(KubeConfigContext(user = "luke-skywalker", cluster = "tatooine"), name = "tatooine-context").toMap(),
            KubeConfigNamedContext(KubeConfigContext(user = "yoda", cluster = "dagobah"), name = "dagobah-context").toMap()
        )
        val users = arrayListOf<Any?>(
            KubeConfigNamedUser(KubeConfigUser(token = "use-the-force"), name = "luke-skywalker").toMap(),
            KubeConfigNamedUser(KubeConfigUser(token = "do-or-do-not"), name = "yoda").toMap()
        )

        // when
        persister.save(
            contexts,
            clusters,
            users,
            emptyMap<String, Any>(),
            "tatooine-context"
        )

        // then
        val actualContent = file.readText()
        assertYamlEquals(expectedContent, actualContent)
    }

    private fun assertYamlEquals(expected: String, actual: String) {
        val mapper = ObjectMapper(YAMLFactory())
        val expectedNode: JsonNode = mapper.readTree(expected)
        val actualNode: JsonNode = mapper.readTree(actual)
        assertThat(actualNode).isEqualTo(expectedNode)
    }

}