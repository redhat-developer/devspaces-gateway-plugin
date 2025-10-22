/*
 * Copyright (c) 2024 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.connection

import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.server.RemoteIDEServerStatus
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConnectionMonitorTest {

    private lateinit var mockRemoteIDEServer: RemoteIDEServer
    private lateinit var connectionMonitor: ConnectionMonitor
    private var serverRestartCalled = false

    @BeforeEach
    fun setUp() {
        mockRemoteIDEServer = mockk(relaxed = true)
        serverRestartCalled = false
        
        connectionMonitor = ConnectionMonitor(
            remoteIDEServer = mockRemoteIDEServer,
            onServerRestart = { serverRestartCalled = true }
        )
    }

    @AfterEach
    fun tearDown() {
        connectionMonitor.stopMonitoring()
    }

    @Test
    fun `#startMonitoring should detect server restart when join link changes`() = runBlocking {
        // given
        val initialStatus = mockk<RemoteIDEServerStatus> {
            every { joinLink } returns "http://localhost:5990/join?token=initial"
        }
        val restartedStatus = mockk<RemoteIDEServerStatus> {
            every { joinLink } returns "http://localhost:5990/join?token=restarted"
        }
        
        every { mockRemoteIDEServer.getStatus() } returnsMany listOf(initialStatus, restartedStatus)
        
        // when
        connectionMonitor.startMonitoring()
        delay(4000) // Wait for at least one health check cycle
        
        // then
        assert(serverRestartCalled) { "Server restart should have been detected" }
        verify(atLeast = 2) { mockRemoteIDEServer.getStatus() }
    }

    @Test
    fun `#startMonitoring should detect server restart after consecutive failures`() = runBlocking {
        // given
        val initialStatus = mockk<RemoteIDEServerStatus> {
            every { joinLink } returns "http://localhost:5990/join?token=test"
        }
        
        every { mockRemoteIDEServer.getStatus() } returns initialStatus andThenThrows Exception("Connection failed") andThenThrows Exception("Connection failed") andThenThrows Exception("Connection failed")
        
        // when
        connectionMonitor.startMonitoring()
        delay(8000) // Wait for multiple health check cycles
        
        // then
        assert(serverRestartCalled) { "Server restart should have been detected after consecutive failures" }
    }

    @Test
    fun `#stopMonitoring should stop health checks`() = runBlocking {
        // given
        every { mockRemoteIDEServer.getStatus() } returns mockk<RemoteIDEServerStatus> {
            every { joinLink } returns "http://localhost:5990/join?token=test"
        }
        
        // when
        connectionMonitor.startMonitoring()
        delay(1000)
        connectionMonitor.stopMonitoring()
        delay(5000) // Wait longer than health check interval
        
        // then
        assert(!connectionMonitor.isActive()) { "Monitor should not be active after stopping" }
    }
}
