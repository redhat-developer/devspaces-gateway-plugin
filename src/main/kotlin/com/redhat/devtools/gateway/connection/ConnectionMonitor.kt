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

import com.intellij.openapi.diagnostic.thisLogger
import com.redhat.devtools.gateway.server.RemoteIDEServer
import kotlinx.coroutines.*
import java.io.IOException

/**
 * Monitors the health of connections to remote IDE servers and detects server restarts.
 */
class ConnectionMonitor(
    private val remoteIDEServer: RemoteIDEServer,
    private val onServerRestart: suspend () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val HEALTH_CHECK_INTERVAL = 3000L // 3 seconds
        private const val HEALTH_CHECK_TIMEOUT = 2000L // 2 seconds
        private const val MAX_CONSECUTIVE_FAILURES = 2
    }

    private var isMonitoring = false
    private var lastKnownJoinLink: String? = null
    private var consecutiveFailures = 0
    private var monitoringJob: Job? = null

    /**
     * Starts monitoring the connection health.
     */
    fun startMonitoring() {
        if (isMonitoring) return
        
        isMonitoring = true
        monitoringJob = scope.launch {
            thisLogger().debug("Starting connection health monitoring")
            
            try {
                // Get initial server state
                val initialStatus = remoteIDEServer.getStatus()
                lastKnownJoinLink = initialStatus.joinLink
                
                while (isActive && isMonitoring) {
                    try {
                        checkServerHealth()
                        delay(HEALTH_CHECK_INTERVAL)
                    } catch (e: CancellationException) {
                        throw e // Re-throw cancellation
                    } catch (e: Exception) {
                        thisLogger().warn("Error during health check", e)
                        delay(HEALTH_CHECK_INTERVAL)
                    }
                }
            } catch (e: CancellationException) {
                thisLogger().debug("Connection monitoring cancelled")
            } catch (e: Exception) {
                thisLogger().error("Connection monitoring failed", e)
            }
        }
    }

    /**
     * Stops monitoring the connection health.
     */
    fun stopMonitoring() {
        isMonitoring = false
        monitoringJob?.cancel()
        monitoringJob = null
        thisLogger().debug("Stopped connection health monitoring")
    }

    /**
     * Performs a health check on the remote server.
     */
    private suspend fun checkServerHealth() {
        try {
            val currentStatus = withTimeout(HEALTH_CHECK_TIMEOUT) {
                remoteIDEServer.getStatus()
            }
            
            val currentJoinLink = currentStatus.joinLink
            
            // Detect server restart by checking if join link changed
            if (lastKnownJoinLink != null && 
                currentJoinLink != null && 
                currentJoinLink != lastKnownJoinLink) {
                
                thisLogger().info("Server restart detected - join link changed from '$lastKnownJoinLink' to '$currentJoinLink'")
                lastKnownJoinLink = currentJoinLink
                consecutiveFailures = 0
                
                // Trigger recovery
                onServerRestart()
                return
            }
            
            // Update known state and reset failure counter
            lastKnownJoinLink = currentJoinLink
            consecutiveFailures = 0
            
        } catch (e: Exception) {
            consecutiveFailures++
            thisLogger().debug("Health check failed (attempt $consecutiveFailures): ${e.message}")
            
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                thisLogger().warn("Multiple consecutive health check failures, server may have restarted")
                consecutiveFailures = 0
                
                // Trigger recovery for potential server restart
                onServerRestart()
            }
        }
    }

    /**
     * Checks if the monitor is currently active.
     */
    fun isActive(): Boolean = isMonitoring && monitoringJob?.isActive == true
}

