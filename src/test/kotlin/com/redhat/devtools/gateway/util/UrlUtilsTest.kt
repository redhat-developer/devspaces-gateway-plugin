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
package com.redhat.devtools.gateway.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

class UrlUtilsTest {

    @Test
    fun `parses fragment parameters`() {
        val link = "tcp://127.0.0.1:57503#jt=abc&p=IU&fp=7D3F&cb=261.22158.277&newUi=true&jb=25.0.2b329.72&remoteId="
        val params = URI(link).fragmentParameters()

        assertThat(params).containsEntry("jt", "abc")
        assertThat(params).containsEntry("p", "IU")
        assertThat(params).containsEntry("cb", "261.22158.277")
        assertThat(params).containsEntry("jb", "25.0.2b329.72")
        assertThat(params).containsEntry("remoteId", "")
    }

    @Test
    fun `returns empty map without fragment`() {
        assertThat(URI("tcp://127.0.0.1:5990").fragmentParameters()).isEmpty()
    }
}
