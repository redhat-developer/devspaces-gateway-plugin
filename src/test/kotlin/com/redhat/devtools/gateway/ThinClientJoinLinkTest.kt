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
package com.redhat.devtools.gateway

import com.redhat.devtools.gateway.util.fragmentParameters
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

class ThinClientJoinLinkTest {

    private val joinLink = URI("tcp://127.0.0.1:5990#jt=token&p=IU")

    private val localPort = 41_234

    @Test
    fun `toLocal swaps only the IDE server port`() {
        // when
        val local = ThinClientJoinLink.toLocal(joinLink, localPort)

        // then - everything but the port is preserved, byte for byte
        assertThat(local.toString()).isEqualTo("tcp://127.0.0.1:$localPort#jt=token&p=IU")
        assertThat(local.fragmentParameters()).isEqualTo(joinLink.fragmentParameters())
    }

    @Test
    fun `toLocal preserves the query`() {
        // given
        val withQuery = URI("tcp://127.0.0.1:5990?context=abc#jt=token")

        // when
        val local = ThinClientJoinLink.toLocal(withQuery, localPort)

        // then
        assertThat(local.rawQuery).isEqualTo(withQuery.rawQuery)
        assertThat(local.port).isEqualTo(localPort)
    }

    @Test
    fun `toLocal fails fast when the link does not address the IDE server port`() {
        // when
        val thrown = assertThrows<IllegalArgumentException> {
            ThinClientJoinLink.toLocal(URI("tcp://127.0.0.1:8080#jt=token"), localPort)
        }

        // then - a malformed link must not silently forward the Guest to the wrong port
        assertThat(thrown.message).contains(ThinClientJoinLink.REMOTE_IDE_PORT.toString())
    }

    @Test
    fun `toLocal fails fast when the link has no port`() {
        val thrown = assertThrows<IllegalArgumentException> {
            ThinClientJoinLink.toLocal(URI("tcp://127.0.0.1#jt=token"), localPort)
        }

        assertThat(thrown.message).contains(ThinClientJoinLink.REMOTE_IDE_PORT.toString())
    }

    @Test
    fun `withReconnectNonce makes the link distinct from the original and from another nonce`() {
        // when
        val first = ThinClientJoinLink.withReconnectNonce(joinLink, 1L)
        val second = ThinClientJoinLink.withReconnectNonce(joinLink, 2L)

        // then - each forced reconnect sends a different link, so the Guest's same-link guard does not fire
        assertThat(first).isNotEqualTo(joinLink)
        assertThat(second).isNotEqualTo(first)
        assertThat(first.fragmentParameters())
            .containsEntry(ThinClientJoinLink.RECONNECT_PARAMETER, "1")
        assertThat(second.fragmentParameters())
            .containsEntry(ThinClientJoinLink.RECONNECT_PARAMETER, "2")
    }

    @Test
    fun `withReconnectNonce preserves existing fragment parameters`() {
        // when
        val nonced = ThinClientJoinLink.withReconnectNonce(joinLink, 7L)

        // then - the Guest merges fragment parameters, so the original ones must survive
        assertThat(nonced.fragmentParameters()).isEqualTo(
            mapOf(
                "jt" to "token",
                "p" to "IU",
                ThinClientJoinLink.RECONNECT_PARAMETER to "7"
            )
        )
    }

    @Test
    fun `withReconnectNonce replaces an existing nonce instead of appending it`() {
        // given
        val nonced = ThinClientJoinLink.withReconnectNonce(joinLink, 7L)

        // when
        val reNonced = ThinClientJoinLink.withReconnectNonce(nonced, 8L)

        // then
        assertThat(reNonced.fragmentParameters()).hasSize(3)
        assertThat(reNonced.fragmentParameters())
            .containsEntry(ThinClientJoinLink.RECONNECT_PARAMETER, "8")
    }

    @Test
    fun `toLocal after withReconnectNonce keeps the nonce`() {
        // when - the recovery watcher builds a forced-reconnect link from a nonced join link
        val nonced = ThinClientJoinLink.withReconnectNonce(joinLink, 42L)
        val local = ThinClientJoinLink.toLocal(nonced, localPort)

        // then
        assertThat(local.port).isEqualTo(localPort)
        assertThat(local.fragmentParameters())
            .containsEntry(ThinClientJoinLink.RECONNECT_PARAMETER, "42")
        assertThat(local.fragmentParameters()).containsEntry("jt", "token")
    }

    @Test
    fun `toLocal keeps existing percent escapes byte for byte`() {
        // given - a link whose path, query and fragment carry escapes
        val escaped = URI("tcp://127.0.0.1:5990/ws%20one?token=a%3Db#jt=a%26b&p=IU")

        // when
        val local = ThinClientJoinLink.toLocal(escaped, localPort)

        // then - re-encoding an escape would point the Guest at a different link
        assertThat(local.rawPath).isEqualTo("/ws%20one")
        assertThat(local.rawQuery).isEqualTo("token=a%3Db")
        assertThat(local.rawFragment).isEqualTo("jt=a%26b&p=IU")
    }

    @Test
    fun `withReconnectNonce keeps existing percent escapes byte for byte`() {
        // given
        val escaped = URI("tcp://127.0.0.1:5990/ws%20one?token=a%3Db#jt=a%26b")

        // when
        val nonced = ThinClientJoinLink.withReconnectNonce(escaped, 3L)

        // then
        assertThat(nonced.rawPath).isEqualTo("/ws%20one")
        assertThat(nonced.rawQuery).isEqualTo("token=a%3Db")
        assertThat(nonced.rawFragment)
            .isEqualTo("jt=a%26b&${ThinClientJoinLink.RECONNECT_PARAMETER}=3")
    }
}
