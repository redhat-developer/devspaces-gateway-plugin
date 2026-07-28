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
import javax.net.ssl.SSLHandshakeException

class ExceptionUtilsTest {

    @Test
    fun `#isTlsRelated detects PKIX errors`() {
        val error = SSLHandshakeException(
            "PKIX path building failed: unable to find valid certification path to requested target"
        )

        assertThat(error.isTlsRelated()).isTrue()
    }

    @Test
    fun `#isTlsRelated ignores unrelated errors`() {
        assertThat(IllegalStateException("not authenticated").isTlsRelated()).isFalse()
    }
}
