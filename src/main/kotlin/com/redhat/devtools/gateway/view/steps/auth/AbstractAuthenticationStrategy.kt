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

import com.intellij.platform.util.progress.RawProgressReporter
import com.redhat.devtools.gateway.auth.tls.TlsContext
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import com.redhat.devtools.gateway.openshift.Cluster
import com.redhat.devtools.gateway.openshift.OpenShiftClientFactory
import com.redhat.devtools.gateway.openshift.Projects
import com.redhat.devtools.gateway.openshift.codeToReasonPhrase
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException

/**
 * Abstract base class for authentication strategies.
 * Provides common functionality and access to shared UI components.
 */
@Suppress("UnstableApiUsage")
abstract class AbstractAuthenticationStrategy(
    protected val tfServer: Any,  // FilteringComboBox<Cluster>
    protected val saveKubeconfig: suspend (Cluster, String, RawProgressReporter) -> Unit
) : AuthenticationStrategy {

    /**
     * Creates a validated API client.
     */
    @Throws(AuthenticationException::class)
    protected fun createValidatedApiClient(
        server: String,
        certificateAuthorityData: String? = null,
        token: String? = null,
        clientCertPem: String? = null,
        clientKeyPem: String? = null,
        tlsContext: TlsContext,
        errorMessage: String? = null
    ): ApiClient = try {
        OpenShiftClientFactory(KubeConfigUtils)
            .create(
                server,
                certificateAuthorityData?.toCharArray(),
                token?.toCharArray(),
                clientCertPem?.toCharArray(),
                clientKeyPem?.toCharArray(),
                tlsContext
            )
            .also { client ->
                require(Projects(client).isAuthenticated()) { errorMessage ?: "Not authenticated" }
            }
    } catch (e: ApiException) {
        throw AuthenticationException(e.codeToReasonPhrase(), e)
    } catch (e: IllegalArgumentException) {
        throw AuthenticationException(e.message ?: "Authentication failed", e)
    }
}
