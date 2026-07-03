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
import com.redhat.devtools.gateway.openshift.Cluster
import com.redhat.devtools.gateway.openshift.apiclient.ClientCertClientBuilder
import com.redhat.devtools.gateway.openshift.apiclient.TokenClientBuilder
import com.redhat.devtools.gateway.openshift.Projects
import com.redhat.devtools.gateway.openshift.codeToReasonPhrase
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible

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
     * Starts a cancellation watcher outside the caller's [CoroutineScope] so it does not
     * block structured-concurrency completion during modal auth progress.
     */
    protected fun launchCancelWatcherOnDefault(
        indicator: ProgressIndicator,
        cancelAction: suspend () -> Unit
    ): Job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        while (isActive) {
            if (indicator.isCanceled) {
                cancelAction()
                return@launch
            }
            @Suppress("ConvertLongToDuration")
            delay(500L)
        }
    }

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
            @Suppress("ConvertLongToDuration")
            delay(500L)
        }
    }

    /**
     * Creates a validated API client on a worker thread.
     * Must not block the [runBlocking] event-loop thread used during modal progress.
     * Builds a token-authenticated API client using the wizard TLS context.
     * Runs synchronously so it is safe inside [ProgressManager.runProcessWithProgressSynchronously].
     */
    protected fun createTokenApiClient(
        server: String,
        token: String,
        tlsContext: TlsContext,
    ): ApiClient =
        TokenClientBuilder(server, token, tlsContext).build()

    /**
     * Creates a validated API client on a worker thread.
     * Cluster TLS trust comes from [tlsContext] (established earlier in the wizard), not from
     * kubeconfig certificate-authority paths that may be stale on this machine.
     */
    @Throws(AuthenticationException::class)
    protected suspend fun createValidatedApiClient(
        server: String,
        token: String? = null,
        clientCert: String? = null,
        clientKey: String? = null,
        tlsContext: TlsContext,
        probeApiAccess: Boolean = true,
        errorMessage: String? = null
    ): ApiClient = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            val certSource = resolveRequiredCertificateSource(clientCert)
            val keySource = resolveRequiredCertificateSource(clientKey)
            val usingToken = token?.isNotEmpty() == true
            val usingClientCert = certSource != null && keySource != null
            require(usingToken.xor(usingClientCert)) {
                "Provide either token OR clientCert + clientKey."
            }

            val apiClient = if (usingClientCert) {
                ClientCertClientBuilder(server, certSource, keySource, tlsContext).build()
            } else {
                TokenClientBuilder(server, token!!, tlsContext).build()
            }
            apiClient
                .also { client ->
                    if (probeApiAccess) {
                        coroutineContext.ensureActive()
                        val authenticated = runInterruptible {
                            Projects(client).isAuthenticated()
                        }
                        require(authenticated) { errorMessage ?: "Not authenticated" }
                    }
                }
        } catch (e: ApiException) {
            throw AuthenticationException(e.codeToReasonPhrase(), e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AuthenticationException(e.message ?: "Authentication failed", e)
        }
    }

    /**
     * Resolves client certificate/key input. Missing files fail authentication.
     */
    private fun resolveRequiredCertificateSource(input: String?): CertificateSource? {
        val source = CertificateSource.fromPathOrPem(input) ?: return null
        source.validate()
        return source
    }
}
