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
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.auth.tls.TlsContext
import com.redhat.devtools.gateway.openshift.Cluster
import io.kubernetes.client.openapi.ApiClient
import javax.swing.JPanel

/**
 * Interface for authentication strategies.
 * Each strategy encapsulates the UI panel creation, authentication logic,
 * and validation for a specific authentication method.
 */
interface AuthenticationStrategy {

    /**
     * Returns the authentication method type for this strategy.
     */
    fun getAuthMethod(): AuthMethod

    /**
     * Returns the tab title for this authentication method.
     */
    fun getTabTitle(): String

    /**
     * Creates the UI panel for this authentication method.
     */
    fun createPanel(): JPanel

    /**
     * Performs the authentication process.
     *
     * @param selectedCluster The cluster to authenticate against
     * @param server The server URL
     * @param certAuthorityData The certificate authority data
     * @param tlsContext The TLS context for secure connections
     * @param reporter The progress reporter
     * @param devSpacesContext The DevSpaces context to update
     * @return true if authentication succeeded, false otherwise
     */
    @Suppress("UnstableApiUsage")
    suspend fun authenticate(
        selectedCluster: Cluster,
        server: String,
        certAuthorityData: String,
        tlsContext: TlsContext,
        reporter: RawProgressReporter,
        devSpacesContext: DevSpacesContext
    )

    /**
     * Determines if the "Next" button should be enabled for this authentication method.
     */
    fun isNextEnabled(): Boolean
}
