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

import com.redhat.devtools.gateway.openshift.DevWorkspace
import io.kubernetes.client.openapi.ApiClient

class DevSpacesContext {
    lateinit var client: ApiClient
    lateinit var devWorkspace: DevWorkspace
    var activeWorkspaces = mutableSetOf<DevWorkspace>()  // Global or companion-level variable
}