/*
 * Copyright (c) 2024-2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.view.steps.auth

import com.intellij.openapi.progress.ProgressIndicator
import com.redhat.devtools.gateway.auth.tls.CertificateSource
import com.redhat.devtools.gateway.auth.tls.TlsContext
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import com.redhat.devtools.gateway.openshift.Cluster
import com.redhat.devtools.gateway.openshift.OpenShiftClientFactory
import com.redhat.devtools.gateway.openshift.Projects
import com.redhat.devtools.gateway.openshift.codeToReasonPhrase
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Abstract base class for authentication strategies.
 * Provides common functionality and access to shared UI components.
 */
abstract class AbstractAuthenticationStrategy(
    protected val tfServer: Any,  // FilteringComboBox<Cluster>
    protected val saveKubeconfig: suspend (Cluster, String, ProgressIndicator) -> Unit
) : AuthenticationStrategy {

    /**
     * Checks if a server/cluster has been selected.
     */
    protected fun isServerSelected(): Boolean {
        return (tfServer as? javax.swing.JComboBox<*>)?.selectedItem != null
    }

    override fun isDirty(saved: Cluster): Boolean = false

    /**
     * Starts a cancellation watcher that polls the progress indicator
     * and cancels the given action when the user cancels the operation.
     */
    protected fun CoroutineScope.launchCancelWatcher(
        indicator: ProgressIndicator,
        cancelAction: suspend () -> Unit
    ): Job = launch(Dispatchers.Default) {
        while (isActive) {
            if (indicator.isCanceled) {
                cancelAction()
                return@launch
            }
            delay(INDICATOR_POLL_DELAY)
        }
    }

    /**
     * Creates a validated API client.
     */
    @Throws(AuthenticationException::class)
    protected fun createValidatedApiClient(
        server: String,
        certAuthority: String? = null,
        token: String? = null,
        clientCert: String? = null,
        clientKey: String? = null,
        tlsContext: TlsContext,
        errorMessage: String? = null
    ): ApiClient = try {
        val caSource = CertificateSource.fromPathOrPem(certAuthority)
        caSource?.validate()
        val certSource = CertificateSource.fromPathOrPem(clientCert)
        certSource?.validate()
        val keySource = CertificateSource.fromPathOrPem(clientKey)
        keySource?.validate()

        OpenShiftClientFactory(KubeConfigUtils)
            .create(
                server,
                caSource,
                token?.toCharArray(),
                certSource,
                keySource,
                tlsContext
            )
            .also { client ->
                require(Projects(client).isAuthenticated()) { errorMessage ?: "Not authenticated" }
            }
    } catch (e: ApiException) {
        throw AuthenticationException(e.codeToReasonPhrase(), e)
    } catch (e: Exception) {
        throw AuthenticationException(e.message ?: "Authentication failed", e)
    }

    companion object {
        val INDICATOR_POLL_DELAY: kotlin.time.Duration = 500.milliseconds
    }
}
