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
package com.redhat.devtools.gateway

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.SequentialLifetimes
import com.redhat.devtools.gateway.connection.ConnectionMonitor  
import com.redhat.devtools.gateway.connection.ConnectionRecovery
import com.redhat.devtools.gateway.openshift.DevWorkspaces
import com.redhat.devtools.gateway.openshift.Pods
import com.redhat.devtools.gateway.server.RemoteIDEServer
import io.kubernetes.client.openapi.ApiException
import kotlinx.coroutines.*
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class DevSpacesConnection(private val devSpacesContext: DevSpacesContext) {
    private var connectionMonitor: ConnectionMonitor? = null
    private var connectionRecovery: ConnectionRecovery? = null
    private var currentClient: ThinClientHandle? = null
    private var lastRecoveryTime: Long = 0
    private val recoveryTimeout = 120000L // 2 minutes cooldown between recoveries
    private val clientLifetimes = SequentialLifetimes(Lifetime.Eternal)
    
    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    fun connect(
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
    ): ThinClientHandle {
        try {
            val client = doConnect(onConnected, onDevWorkspaceStopped, onDisconnected)
            currentClient = client
            
            // Set up connection monitoring to detect server restarts
            setupConnectionRecovery(onConnected, onDisconnected, onDevWorkspaceStopped)
            
            return client
        } catch (e: Exception) {
            devSpacesContext.isConnected = false
            throw e
        }
    }

    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    private fun doConnect(
        onConnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        onDisconnected: () -> Unit
    ): ThinClientHandle {
        startAndWaitDevWorkspace()

        val remoteIdeServer = RemoteIDEServer(devSpacesContext)
        
        // Wait for server to be ready before getting status
        remoteIdeServer.waitServerReady()
        
        val remoteIdeServerStatus = remoteIdeServer.getStatus()
        val joinLink = remoteIdeServerStatus.joinLink
            ?: throw IOException("Could not connect, remote IDE is not ready. No join link present.")

        val pods = Pods(devSpacesContext.client)
        // ✅ Dynamically find a free local port
        val localPort = findFreePort()
        val forwarder = pods.forward(remoteIdeServer.pod, localPort, 5990)
        pods.waitForForwardReady(localPort)
        val effectiveJoinLink = joinLink.replace(":5990", ":$localPort")
        
        thisLogger().warn("Calling LinkedClientManager.startNewClient()")
        thisLogger().warn("Creating new client with sequential lifetime - this should terminate any old clients")
        val newClientLifetime = clientLifetimes.next()
        thisLogger().warn("Got new sequential lifetime: $newClientLifetime")
        
        val client = LinkedClientManager
            .getInstance()
            .startNewClient(
                newClientLifetime,
                URI(effectiveJoinLink),
                "",
                onConnected,
                false
            )
            
        thisLogger().warn("New client created with lifetime: ${client.lifetime}")
        
        // Monitor for lifetime termination to see if old clients are closed
        client.lifetime.onTermination {
            thisLogger().warn("CLIENT LIFETIME TERMINATED - this should close any stuck dialogs")
        }
            
        thisLogger().warn("LinkedClientManager.startNewClient() returned ThinClientHandle, now waiting for connection to be ready")
        
        // Set up timeout for waiting for the ThinClient to actually connect
        // This is where the "Connecting to remote host..." dialog appears
        val connectionReady = CompletableFuture<Boolean>()
        val timeoutMillis = 60000L // 1 minute for testing
        
        // Monitor for successful connection
        client.onClientPresenceChanged.advise(client.lifetime) {
            thisLogger().warn("ThinClient presence changed - connection ready!")
            connectionReady.complete(true)
        }
        
        // Monitor for connection failure  
        client.clientClosed.advise(client.lifetime) {
            thisLogger().warn("ThinClient closed during connection setup")
            if (!connectionReady.isDone) {
                connectionReady.complete(false)
            }
        }
        
        client.clientFailedToOpenProject.advise(client.lifetime) { errorCode ->
            thisLogger().warn("ThinClient failed to open project: $errorCode")
            if (!connectionReady.isDone) {
                connectionReady.complete(false)
            }
        }
        
        // Set up timeout timer
        val timeoutTimer = java.util.Timer()
        thisLogger().warn("Setting up ThinClient ready timeout for ${timeoutMillis/1000} seconds")
        timeoutTimer.schedule(object : java.util.TimerTask() {
            override fun run() {
                thisLogger().error("=== CONNECTION READY TIMEOUT FIRED ===")
                if (!connectionReady.isDone) {
                    thisLogger().error("ThinClient connection timed out after ${timeoutMillis/1000} seconds - terminating")
                    // Try to close the client connection
                    connectionReady.complete(false)
                }
            }
        }, timeoutMillis)
        
        // Wait for connection to be ready or timeout
        try {
            val success = connectionReady.get(timeoutMillis + 5000, TimeUnit.MILLISECONDS)
            timeoutTimer.cancel()
            if (!success) {
                throw IOException("Connection failed or timed out after ${timeoutMillis/1000} seconds")
            }
            thisLogger().warn("ThinClient connection established successfully")
        } catch (e: Exception) {
            timeoutTimer.cancel()
            // Let the lifetime handle cleanup naturally
            throw IOException("Connection timed out or failed: ${e.message}", e)
        }

        client.run {
            lifetime.onTermination {
                try {
                    forwarder.close()
                } catch (_: Exception) {
                    // Ignore cleanup errors
                }
            }
            lifetime.onTermination {
                if (remoteIdeServer.waitServerTerminated())
                    DevWorkspaces(devSpacesContext.client)
                        .stop(
                            devSpacesContext.devWorkspace.namespace,
                            devSpacesContext.devWorkspace.name
                        )
                        .also { onDevWorkspaceStopped() }
            }
            lifetime.onTermination { devSpacesContext.isConnected = false }
            lifetime.onTermination(onDisconnected)
            lifetime.onTermination { 
                // Stop monitoring when connection terminates
                connectionMonitor?.stopMonitoring()
            }
        }

        return client
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }

    @Throws(IOException::class, ApiException::class)
    private fun startAndWaitDevWorkspace() {
        if (!devSpacesContext.devWorkspace.started) {
            DevWorkspaces(devSpacesContext.client)
                .start(
                    devSpacesContext.devWorkspace.namespace,
                    devSpacesContext.devWorkspace.name
                )
        }

        if (!DevWorkspaces(devSpacesContext.client)
                .waitPhase(
                    devSpacesContext.devWorkspace.namespace,
                    devSpacesContext.devWorkspace.name,
                    DevWorkspaces.RUNNING,
                    DevWorkspaces.RUNNING_TIMEOUT
                )
        ) throw IOException(
            "DevWorkspace '${devSpacesContext.devWorkspace.name}' is not running after ${DevWorkspaces.RUNNING_TIMEOUT} seconds"
        )
    }
    
    private fun setupConnectionRecovery(
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit
    ) {
        val remoteIDEServer = RemoteIDEServer(devSpacesContext)
        
        // Initialize connection recovery
        connectionRecovery = ConnectionRecovery(devSpacesContext)
        
        // Set up connection monitoring to detect server restarts
        connectionMonitor = ConnectionMonitor(
            remoteIDEServer = remoteIDEServer,
            onServerRestart = {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastRecovery = currentTime - lastRecoveryTime
                
                if (timeSinceLastRecovery < recoveryTimeout) {
                    thisLogger().warn("=== REMOTE IDE SERVER RESTART DETECTED ===")
                    thisLogger().warn("Ignoring restart detection - too soon after last recovery (${timeSinceLastRecovery/1000}s ago, need ${recoveryTimeout/1000}s cooldown)")
                    return@ConnectionMonitor
                }
                
                thisLogger().warn("=== REMOTE IDE SERVER RESTART DETECTED ===")
                thisLogger().warn("This should prevent the stuck 'Connecting to remote host...' dialog")
                lastRecoveryTime = currentTime
                
                // Launch recovery in background to prevent blocking
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    try {
                        thisLogger().warn("Waiting 30 seconds for remote IDE to finish restarting before attempting recovery")
                        delay(30000) // Wait 30 seconds for the remote IDE to restart
                        thisLogger().warn("Starting proactive connection recovery to prevent Gateway stuck dialog")
                        
                        // Terminate old client before creating new one
                        currentClient?.let { oldClient ->
                            thisLogger().warn("Terminating old client before recovery")
                            try {
                                // Force close the old client by creating a new sequential lifetime
                                val terminatingLifetime = clientLifetimes.next() 
                                thisLogger().warn("Created terminating lifetime for old client")
                            } catch (e: Exception) {
                                thisLogger().warn("Could not terminate old client: ${e.message}")
                            }
                        }
                        
                        val newClient = connectionRecovery?.recoverConnection(
                            oldClient = currentClient,
                            onConnected = {
                                thisLogger().warn("Recovery onConnected callback triggered - new IDE should open")
                                onConnected()
                            },
                            onDisconnected = onDisconnected,
                            onDevWorkspaceStopped = onDevWorkspaceStopped,
                            clientLifetime = clientLifetimes.next(), // This will terminate any remaining old client
                            onRecoveryProgress = { message ->
                                thisLogger().warn("Recovery progress: $message")
                            }
                        )
                        
                        if (newClient != null) {
                            thisLogger().warn("SUCCESS: New ThinClientHandle created with updated join link")
                            currentClient = newClient
                            
                            // Set up monitoring for the new client
                            newClient.lifetime.onTermination { 
                                connectionMonitor?.stopMonitoring()
                            }
                            
                            // Try to close any stuck dialogs after successful recovery
                            try {
                                thisLogger().warn("Attempting to close stuck dialogs after successful recovery")
                                // Give the new connection a moment to establish
                                delay(2000)
                                
                                // Try to trigger the onConnected callback to close dialogs
                                thisLogger().warn("Recovery complete - triggering onConnected to close dialogs")
                                onConnected()
                                
                            } catch (e: Exception) {
                                thisLogger().warn("Could not auto-close stuck dialogs: ${e.message}")
                            }
                            
                        } else {
                            thisLogger().error("FAILED: Could not create new ThinClientHandle - Gateway dialog may still get stuck")
                        }
                        
                        } catch (e: Exception) {
                            // Don't log TimeoutCancellationException as error - it's a control flow exception
                            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                                thisLogger().warn("Connection recovery timed out: ${e.message}")
                            } else {
                                thisLogger().error("Connection recovery failed with exception", e)
                            }
                        }
                }
            }
        )
        
        connectionMonitor?.startMonitoring()
        thisLogger().warn("Connection monitoring started - will detect server restarts and prevent stuck dialogs")
    }
}