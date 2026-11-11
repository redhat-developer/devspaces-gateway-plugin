/*
 * Copyright (c) 2026 Red Hat, Inc.
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

import com.redhat.devtools.gateway.devworkspace.WorkspaceEditorKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DevSpacesIconsTest {

    @Test
    fun `#getEditorIcon returns an icon for every WorkspaceEditorKind`() {
        WorkspaceEditorKind.entries.forEach { kind ->
            assertThat(DevSpacesIcons.getEditorIcon(kind)).isNotNull()
        }
    }
}
