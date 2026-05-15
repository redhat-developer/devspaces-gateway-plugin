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
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.auth.tls.TlsContext
import com.redhat.devtools.gateway.openshift.Cluster
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
     * @param certAuthority The certificate authority data
     * @param tlsContext The TLS context for secure connections
     * @param indicator The progress indicator
     * @param devSpacesContext The DevSpaces context to update
     * @return true if authentication succeeded, false otherwise
     */
    suspend fun authenticate(
        selectedCluster: Cluster,
        server: String,
        certAuthority: String?,
        tlsContext: TlsContext,
        indicator: ProgressIndicator,
        devSpacesContext: DevSpacesContext
    )

    /**
     * Determines if the "Next" button should be enabled for this authentication method.
     */
    fun isNextEnabled(): Boolean

    /**
     * Returns `true` if the current values in this strategy differs from the given config
     */
    fun isDirty(saved: Cluster): Boolean
}
