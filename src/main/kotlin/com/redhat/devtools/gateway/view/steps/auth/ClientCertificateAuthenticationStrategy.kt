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
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import javax.swing.event.DocumentListener
import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.view.ui.PasteClipboardMenu
import com.redhat.devtools.gateway.auth.tls.browseCertificate
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.auth.tls.TlsContext
import com.redhat.devtools.gateway.openshift.Cluster
import javax.swing.JPanel

/**
 * Authentication strategy for client certificate authentication.
 */
class ClientCertificateAuthenticationStrategy(
    tfServer: Any,
    saveKubeconfig: suspend (Cluster, String, ProgressIndicator) -> Unit,
    private val saveKubeconfigWithCert: suspend (Cluster, String, String, ProgressIndicator) -> Unit,
    private val onFieldChanged: () -> DocumentListener
) : AbstractAuthenticationStrategy(
    tfServer,
    saveKubeconfig
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
            cell(tfClientCert)
                .align(Align.FILL)
                .resizableColumn()
                .comment("Provide the path to a PEM file or paste the PEM content")
            button("Browse...") {
                browseCertificate(tfClientCert, "Select Client Certificate File")
            }
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.client_key")) {
            cell(tfClientKey)
                .align(Align.FILL)
                .resizableColumn()
                .comment("Provide the path to a PEM file or paste the PEM content")
            button("Browse...") {
                browseCertificate(tfClientKey, "Select Client Key File")
            }
        }
    }

    override suspend fun authenticate(
        selectedCluster: Cluster,
        server: String,
        certAuthority: String?,
        tlsContext: TlsContext,
        indicator: ProgressIndicator,
        devSpacesContext: DevSpacesContext
    ) {
        indicator.text = "Validating client certificate..."

        val clientCert = tfClientCert.text
        val clientKey = tfClientKey.text

        val client = createValidatedApiClient(
            server,
            certAuthority,
            null,
            clientCert,
            clientKey,
            tlsContext,
            "Authentication failed: invalid client certificate or key."
        )

        saveKubeconfigWithCert(selectedCluster, clientCert, clientKey, indicator)
        devSpacesContext.client = client
    }

    override fun isNextEnabled(): Boolean =
        isServerSelected()
                && tfClientCert.text.isNotBlank()
                && tfClientKey.text.isNotBlank()

    /**
     * Dirty vs kubeconfig only once both PEM paths/contents are filled; an empty tab after switching is not dirty.
     */
    override fun isDirty(saved: Cluster): Boolean {
        val cert = tfClientCert.text.trim()
        val key = tfClientKey.text.trim()
        if (cert.isEmpty() || key.isEmpty()) return false
        return cert != (saved.clientCert?.value ?: "").trim()
            || key != (saved.clientKey?.value ?: "").trim()
    }
}
