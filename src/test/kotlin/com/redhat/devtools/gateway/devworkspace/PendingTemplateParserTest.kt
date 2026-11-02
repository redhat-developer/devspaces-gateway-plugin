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
package com.redhat.devtools.gateway.devworkspace

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PendingTemplateParserTest {

    @Test
    fun `#parse returns null for a missing payload`() {
        assertThat(PendingTemplateParser.parse(null)).isNull()
    }

    @Test
    fun `#parse rejects a blank payload`() {
        assertThatThrownBy {
            PendingTemplateParser.parse("  ")
        }
            .isInstanceOf(InvalidRestartRequestException::class.java)
            .hasMessageContaining("blank")
    }

    @Test
    fun `#parse rejects an empty template`() {
        assertThatThrownBy {
            PendingTemplateParser.parse("""{"version":1,"template":{}}""")
        }
            .isInstanceOf(InvalidRestartRequestException::class.java)
            .hasMessageContaining("empty template")
    }

    @Test
    fun `#parse returns the template of a valid payload`() {
        // given
        val payload = """
            {"version":1,"template":{"components":[{"name":"tools"}]}}
        """.trimIndent()

        // when
        val template = PendingTemplateParser.parse(payload)

        // then
        assertThat(template).isNotNull
        assertThat(template!!["components"]).isNotNull
    }

    @Test
    fun `#parse rejects an unsupported version`() {
        assertThatThrownBy {
            PendingTemplateParser.parse("""{"version":2,"template":{}}""")
        }
            .isInstanceOf(InvalidRestartRequestException::class.java)
            .hasMessageContaining("Unsupported")
    }

    @Test
    fun `#parse rejects a payload without a template`() {
        assertThatThrownBy {
            PendingTemplateParser.parse("""{"version":1}""")
        }
            .isInstanceOf(InvalidRestartRequestException::class.java)
            .hasMessageContaining("no template")
    }

    @Test
    fun `#parse rejects invalid JSON`() {
        assertThatThrownBy {
            PendingTemplateParser.parse("{not json")
        }
            .isInstanceOf(InvalidRestartRequestException::class.java)
            .hasMessageContaining("Invalid devfile template payload")
            .hasCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException::class.java)
    }
}
