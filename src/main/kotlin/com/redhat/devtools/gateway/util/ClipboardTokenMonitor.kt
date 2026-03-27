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

import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Monitors the system clipboard for OpenShift tokens and notifies listeners when detected.
 *
 * This class polls the clipboard at regular intervals and detects when a valid OpenShift token
 * (format: sha256~[base64-like-chars]) appears. When a new token is detected, registered listeners
 * are notified.
 *
 * ## Usage
 * ```kotlin
 * val monitor = ClipboardTokenMonitor()
 * monitor.addListener { token ->
 *     println("Token detected: $token")
 * }
 * monitor.start()
 * // ... later ...
 * monitor.stop()
 * ```
 *
 * @param pollingIntervalMs Interval between clipboard checks in milliseconds. Defaults to 500ms.
 * @param clipboardReader Reader for accessing clipboard content. Defaults to system clipboard.
 */
class ClipboardTokenMonitor(
    private val pollingIntervalMs: Long = 500,
    private val clipboardReader: ClipboardReader = SystemClipboardReader()
) {

    companion object {
        private val OPENSHIFT_TOKEN_REGEX = Regex("^sha256~[A-Za-z0-9_-]{20,}$")

        /**
         * Checks if a string is a valid OpenShift token.
         * Format: sha256~[base64-like-characters with at least 20 chars]
         *
         * @param token The string to check
         * @return true if the token matches OpenShift token format, false otherwise
         */
        fun isOpenShiftToken(token: String?): Boolean {
            if (token == null) return false
            return OPENSHIFT_TOKEN_REGEX.matches(token.trim())
        }
    }

    private val listeners = mutableListOf<TokenDetectedListener>()
    private var lastClipboardValue: String? = null
    private var pollingJob: Job? = null

    /**
     * Listener interface for token detection events.
     */
    fun interface TokenDetectedListener {
        /**
         * Called when a new OpenShift token is detected in the clipboard.
         *
         * @param token The detected token string
         */
        fun onTokenDetected(token: String)
    }

    /**
     * Adds a listener to be notified when tokens are detected.
     *
     * @param listener The listener to add
     */
    fun addListener(listener: TokenDetectedListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Removes a previously registered listener.
     *
     * @param listener The listener to remove
     */
    fun removeListener(listener: TokenDetectedListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * Starts monitoring the clipboard for tokens.
     * Polling runs on a background coroutine.
     */
    fun start() {
        if (pollingJob?.isActive == true) {
            return
        }

        this.pollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val value = readClipboardText()

                if (value != null && value != lastClipboardValue) {
                    lastClipboardValue = value

                    if (isOpenShiftToken(value)) {
                        notifyListeners(value)
                    }
                }

                delay(pollingIntervalMs.milliseconds)
            }
        }
    }

    /**
     * Stops monitoring the clipboard.
     */
    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Checks the clipboard immediately for a token without starting continuous polling.
     *
     * @return The token if one is found, null otherwise
     */
    fun checkNow(): String? {
        val token = readClipboardText()
        return if (isOpenShiftToken(token)) token else null
    }

    /**
     * Reads the current text content from the clipboard.
     *
     * @return The clipboard text or null if empty or not text
     */
    private fun readClipboardText(): String? {
        return clipboardReader.readText()
    }

    /**
     * Notifies all registered listeners of a detected token.
     */
    private fun notifyListeners(token: String) {
        val listenersCopy = synchronized(listeners) { listeners.toList() }
        listenersCopy.forEach { listener ->
            try {
                listener.onTokenDetected(token)
            } catch (_: Exception) {
                // Ignore listener exceptions
            }
        }
    }
}
