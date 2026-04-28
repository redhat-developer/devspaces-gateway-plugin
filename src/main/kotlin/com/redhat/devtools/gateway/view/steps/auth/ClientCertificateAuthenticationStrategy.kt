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
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import javax.swing.event.DocumentListener
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.view.ui.PasteClipboardMenu
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.auth.tls.TlsContext
import com.redhat.devtools.gateway.openshift.Cluster
import javax.swing.JPanel

/**
 * Authentication strategy for client certificate authentication.
 */
@Suppress("UnstableApiUsage")
class ClientCertificateAuthenticationStrategy(
    tfServer: Any,
    saveKubeconfig: suspend (Cluster, String, RawProgressReporter) -> Unit,
    saveKubeconfigCert: suspend (Cluster, String, String, RawProgressReporter) -> Unit,
    private val onFieldChanged: () -> DocumentListener
) : AbstractAuthenticationStrategy(
    tfServer,
    saveKubeconfig,
    saveKubeconfigCert
) {

    val tfClientCert = JBTextField().apply {
        document.addDocumentListener(onFieldChanged())
        PasteClipboardMenu.addTo(this)
    }

    val tfClientKey = JBTextField().apply {
        document.addDocumentListener(onFieldChanged())
        PasteClipboardMenu.addTo(this)
    }

    override fun getAuthMethod(): AuthMethod = AuthMethod.CLIENT_CERTIFICATE

    override fun getTabTitle(): String =
        DevSpacesBundle.message("connector.wizard_step.openshift_connection.tab.client_certificate")

    override fun createPanel(): JPanel = panel {
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.client_certificate")) {
            cell(tfClientCert).align(Align.FILL)
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.client_key")) {
            cell(tfClientKey).align(Align.FILL)
        }
    }

    override suspend fun authenticate(
        selectedCluster: Cluster,
        server: String,
        certAuthorityData: String,
        tlsContext: TlsContext,
        reporter: RawProgressReporter,
        devSpacesContext: DevSpacesContext
    ) {
        reporter.text("Validating client certificate...")

        val clientCertPem = tfClientCert.text
        val clientKeyPem = tfClientKey.text

        val client = createValidatedApiClient(
            server,
            certAuthorityData,
            null,
            clientCertPem,
            clientKeyPem,
            tlsContext,
            "Authentication failed: invalid client certificate or key."
        )

        saveKubeconfigCert(selectedCluster, clientCertPem, clientKeyPem, reporter)
        devSpacesContext.client = client
    }

    override fun isNextEnabled(): Boolean =
        tfClientCert.text.isNotBlank() && tfClientKey.text.isNotBlank()
}
