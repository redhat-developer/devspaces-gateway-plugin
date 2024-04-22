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

import com.redhat.devtools.gateway.help.DEVSPACES_DOC_LINK
import com.redhat.devtools.gateway.view.DevSpacesMainView
import com.jetbrains.gateway.api.GatewayConnector
import com.jetbrains.gateway.api.GatewayConnectorDocumentationPage
import com.jetbrains.gateway.api.GatewayConnectorView
import com.jetbrains.rd.util.lifetime.Lifetime
import javax.swing.Icon

class DevSpacesConnector : GatewayConnector {
    override fun getConnectorId() = "devspaces.gateway.connector"

    override val icon: Icon
        get() = DevSpacesIcons.LOGO

    override fun createView(lifetime: Lifetime): GatewayConnectorView {
        return DevSpacesMainView()
    }

    override fun getActionText(): String {
        return DevSpacesBundle.message("connector.action.text")
    }

    override fun getDescription(): String {
        return DevSpacesBundle.message("connector.description")
    }

    override fun getDocumentationAction() = GatewayConnectorDocumentationPage(DEVSPACES_DOC_LINK)

    override fun getTitle(): String {
        return DevSpacesBundle.message("connector.title")
    }

    override fun isAvailable(): Boolean {
        return true
    }
}