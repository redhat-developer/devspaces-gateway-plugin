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
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

class PodsTest {

    private val serverData = "from server"

    private lateinit var client: ApiClient
    private lateinit var pods: Pods

    private lateinit var buffer: ByteArray

    private val pod = V1Pod().apply {
       metadata = V1ObjectMeta().apply {
            name = "luke-skywalker"
        }
    }

    private val remotePort = 8080
    private var localPort = 0

    @BeforeEach
    fun setUp() {
        client = mockk(relaxed = true)
        pods = Pods(client)
        localPort = findFreePort()
        buffer = ByteArray(1024)
        mockkConstructor(PortForward::class)
    }

    @AfterEach
    fun tearDown() {
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
}