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
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.openshift.Pods
import com.redhat.devtools.gateway.server.RemoteIDEServer
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.URI

/**
 * Handles recovery of ThinClientHandle connections when remote IDE servers restart.
 */
class ConnectionRecovery(
    private val devSpacesContext: DevSpacesContext,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val RECOVERY_DELAY_BASE = 2000L // Base delay: 2 seconds
        private const val RECOVERY_TIMEOUT = 300000L // 5 minutes total timeout to allow for server restart
    }

    /**
     * Attempts to recover a connection by creating a new ThinClientHandle.
     */
    suspend fun recoverConnection(
        oldClient: ThinClientHandle?,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        clientLifetime: Lifetime = Lifetime.Eternal,
        onRecoveryProgress: (String) -> Unit = {}
    ): ThinClientHandle? {
        return withTimeout(RECOVERY_TIMEOUT) {
            thisLogger().info("Starting connection recovery...")
            onRecoveryProgress("Reconnecting to remote host...")

            var lastException: Exception? = null

            repeat(MAX_RECOVERY_ATTEMPTS) { attempt ->
                try {
                    val delay = RECOVERY_DELAY_BASE * (attempt + 1)
                    if (attempt > 0) {
                        thisLogger().debug("Recovery attempt ${attempt + 1} after ${delay}ms delay")
                        onRecoveryProgress("Retrying connection (attempt ${attempt + 1}/$MAX_RECOVERY_ATTEMPTS)...")
                        delay(delay)
                    }

                    // Clean up old connection
                    oldClient?.let { cleanupOldConnection(it) }

                    // Create new connection
                    val newClient = createNewConnection(onConnected, onDisconnected, onDevWorkspaceStopped, clientLifetime)
                    
                    thisLogger().info("Connection recovery successful on attempt ${attempt + 1}")
                    onRecoveryProgress("Connection restored successfully")
                    return@withTimeout newClient

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastException = e
                    thisLogger().warn("Recovery attempt ${attempt + 1} failed: ${e.message}")
                    onRecoveryProgress("Recovery attempt ${attempt + 1} failed, retrying...")
                }
            }

            // All recovery attempts failed
            thisLogger().error("Connection recovery failed after $MAX_RECOVERY_ATTEMPTS attempts", lastException)
            onRecoveryProgress("Connection recovery failed")
            throw lastException ?: IOException("Connection recovery failed after $MAX_RECOVERY_ATTEMPTS attempts")
        }
    }

    /**
     * Creates a new ThinClientHandle connection.
     */
    private suspend fun createNewConnection(
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        clientLifetime: Lifetime = Lifetime.Eternal
    ): ThinClientHandle = withContext(Dispatchers.IO) {
        val remoteIdeServer = RemoteIDEServer(devSpacesContext)
        
        // Wait for server to be ready after restart (longer timeout for recovery)
        remoteIdeServer.waitServerReady(180) // 3 minutes for restart recovery
        
        val remoteIdeServerStatus = remoteIdeServer.getStatus()
        val joinLink = remoteIdeServerStatus.joinLink
            ?: throw IOException("Could not recover connection, remote IDE is not ready. No join link present.")

        val pods = Pods(devSpacesContext.client)
        val localPort = findFreePort()
        val forwarder = pods.forward(remoteIdeServer.pod, localPort, 5990)
        
        // Wait for port forwarding to be ready
        pods.waitForForwardReady(localPort)
        val effectiveJoinLink = joinLink.replace(":5990", ":$localPort")

        thisLogger().warn("ConnectionRecovery: Creating recovery client with lifetime: $clientLifetime")
        val client = LinkedClientManager
            .getInstance()
            .startNewClient(
                clientLifetime, // Use provided lifetime (will be sequential lifetime)
                URI(effectiveJoinLink),
                "",
                onConnected,
                false
            )
        
        thisLogger().warn("ConnectionRecovery: Recovery client created with lifetime: ${client.lifetime}")
        
        // Monitor recovery client lifetime termination
        client.lifetime.onTermination {
            thisLogger().warn("RECOVERY CLIENT LIFETIME TERMINATED")
        }

        // Set up cleanup handlers
        client.lifetime.onTermination {
            try {
                forwarder.close()
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }
        
        client.lifetime.onTermination {
            if (remoteIdeServer.waitServerTerminated()) {
                devSpacesContext.devWorkspace.let { workspace ->
                    try {
                        // Note: We don't stop the workspace during recovery as it might be needed for future connections
                        thisLogger().debug("Server terminated during recovery")
                    } catch (e: Exception) {
                        thisLogger().warn("Error handling server termination during recovery", e)
                    }
                }
            }
        }
        
        client.lifetime.onTermination { devSpacesContext.isConnected = false }
        client.lifetime.onTermination(onDisconnected)

        return@withContext client
    }

    /**
     * Properly cleans up an old connection.
     * Note: In JetBrains Gateway 2025.1, we cannot call terminate() on lifetimes.
     * The stuck dialog issue needs to be handled differently.
     */
    private fun cleanupOldConnection(oldClient: ThinClientHandle) {
        try {
            thisLogger().warn("Cleaning up old connection - old client lifetime will be managed by Gateway framework")
            thisLogger().warn("Note: The stuck 'Connecting to remote host...' dialog may need manual cancellation")
            // Unfortunately, we cannot terminate the old client's lifetime in Gateway 2025.1
            // The user will need to manually cancel the old dialog for now
        } catch (e: Exception) {
            thisLogger().warn("Error during old connection cleanup", e)
        }
    }

    /**
     * Finds a free local port for port forwarding.
     */
    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }
}

