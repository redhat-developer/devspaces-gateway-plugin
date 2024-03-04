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
package com.github.devspaces.gateway.settings

import com.intellij.openapi.components.*

@Service(Service.Level.APP)
@State(
    name = "DevSpacesOpenShiftConnection",
    storages = [Storage("devspaces-openshift-connection.xml", roamingType = RoamingType.DISABLED, exportable = false)]
)
class DevSpacesSettings : SimplePersistentStateComponent<DevSpacesState>(DevSpacesState())

class DevSpacesState : BaseState() {
    var server by string()
    var token by string()
}
