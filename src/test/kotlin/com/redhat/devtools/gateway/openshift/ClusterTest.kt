/*
 * Copyright (c) 2025 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.openshift

import com.redhat.devtools.gateway.auth.tls.CertificateSource
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.*

class ClusterTest {

    @Test
    fun `#Cluster constructor creates instance with name, url, and token`() {
        // given
        // when
        val cluster = Cluster(name = "death-star", url = "https://api.deathstar.empire", token = "empire-token-4ls")

        // then
        assertThat(cluster.name).isEqualTo("death-star")
        assertThat(cluster.url).isEqualTo("https://api.deathstar.empire")
        assertThat(cluster.token).isEqualTo("empire-token-4ls")
        assertThat(cluster.id).isEqualTo("death-star@api.deathstar.empire")
    }

    @Test
    fun `#Cluster constructor creates instance with default null token`() {
        // given
        // when
        val cluster = Cluster(name = "tatooine", url = "https://api.tatooine.galaxy")

        // then
        assertThat(cluster.name).isEqualTo("tatooine")
        assertThat(cluster.url).isEqualTo("https://api.tatooine.galaxy")
        assertThat(cluster.token).isNull()
        assertThat(cluster.id).isEqualTo("tatooine@api.tatooine.galaxy")
    }

    @Test
    fun `#toString returns formatted string`() {
        // given
        // when
        val cluster = Cluster(
            name = "millennium-falcon",
            url = "https://api.falcon.ship",
            token = "solo-token-123")

        // then
        assertThat(cluster.toString()).isEqualTo("millennium-falcon (https://api.falcon.ship)")
    }

    @Test
    fun `#id property returns formatted id removing protocol`() {
        // given
        // when
        assertThat(Cluster(name = "x-wing", url = "https://api.xwing.rebel").id)
            .isEqualTo("x-wing@api.xwing.rebel")
        assertThat(Cluster("tie-fighter", "http://api.tie.empire").id)
            .isEqualTo("tie-fighter@api.tie.empire")
        assertThat(Cluster("star-destroyer", "https://api.destroyer.empire:8443").id)
            .isEqualTo("star-destroyer@api.destroyer.empire:8443")
        assertThat(Cluster("jedi-council", "https://api.jedi.temple/path").id)
            .isEqualTo("jedi-council@api.jedi.temple/path")
    }

    @Test
    fun `#equals returns true for clusters with same properties`() {
        // given
        // when
        val cluster1 = Cluster(name = "r2d2", url = "https://api.robots.galaxy", token = "droid-token-1")
        val cluster2 = Cluster(name = "r2d2", url = "https://api.robots.galaxy", token = "droid-token-1")
        val cluster3 = Cluster(name = "c3po", url = "https://api.robots.galaxy", token = "droid-token-1")

        // then
        assertThat(cluster1).isEqualTo(cluster2)
        assertThat(cluster1).isNotEqualTo(cluster3)
    }

    @Test
    fun `#hashCode returns same value for clusters with same properties`() {
        // given
        // when
        val cluster1 = Cluster(name = "r2d2", url = "https://api.robots.galaxy", token = "droid-token-1")
        val cluster2 = Cluster(name = "r2d2", url = "https://api.robots.galaxy", token = "droid-token-1")

        // then
        assertThat(cluster1.hashCode()).isEqualTo(cluster2.hashCode())
    }

    @Test
    fun `#copy method creates new instance with modified properties`() {
        // given
        val original = Cluster(name = "obi-wan", url = "https://api.kenobi.jedi", token = "kenobi-token-1")

        // when
        val copy = original.copy(name = "ben-kenobi")

        // then
        assertThat(copy.name).isEqualTo("ben-kenobi")
        assertThat(copy.url).isEqualTo("https://api.kenobi.jedi")
        assertThat(copy.token).isEqualTo("kenobi-token-1")
    }

    @Test
    fun `#name returns url without scheme nor path`() {
        // given
        // when
        // then
        assertThat(Cluster.fromNameAndUrl("https://jedi-temple.galaxy")?.name).isEqualTo("jedi-temple.galaxy")
        assertThat(Cluster.fromNameAndUrl("http://local-transport:8080")?.name).isEqualTo("local-transport-8080")
        assertThat(Cluster.fromNameAndUrl("https://rebel-base.galaxy:443/")?.name).isEqualTo("rebel-base.galaxy-443")
        assertThat(Cluster.fromNameAndUrl("https://sith-tower.galaxy:9090/api/v1")?.name).isEqualTo("sith-tower.galaxy-9090")
    }

    @Test
    fun `#id returns name@url without scheme, port nor path`() {
        // given
        // when
        assertThat(Cluster(name = "x-wing", url = "https://api.xwing.rebel").id)
            .isEqualTo("x-wing@api.xwing.rebel")
        assertThat(Cluster("tie-fighter", "http://localhost:8080").id)
            .isEqualTo("tie-fighter@localhost:8080")
        assertThat(Cluster("star-destroyer", "https://api.destroyer.empire:443/").id)
            .isEqualTo("star-destroyer@api.destroyer.empire:443/")
        assertThat(Cluster("jedi-council", "https://api.jedi.temple:9090/api/v1").id)
            .isEqualTo("jedi-council@api.jedi.temple:9090/api/v1")
    }

    @Test
    fun `#fromNameAndUrl creates cluster from URL-only input`() {
        // given
        val url = "https://api.che-dev.x6e0.p1.openshiftapps.com:6443"

        // when
        val cluster = Cluster.fromNameAndUrl(url)

        // then
        assertThat(cluster).isNotNull()
        assertThat(cluster?.url).isEqualTo(url)
        assertThat(cluster?.name).isNotNull().isNotEmpty()
    }

    @Test
    fun `#fromNameAndUrl creates cluster from name and URL format`() {
        // given
        val name = "x-wing"
        val url = "https://api.xwing.rebel"
        val input = "$name ($url)"

        // when
        val cluster = Cluster.fromNameAndUrl(input)

        // then
        assertThat(cluster).isNotNull()
        assertThat(cluster?.name).isEqualTo(name)
        assertThat(cluster?.url).isEqualTo(url)
    }

    @Test
    fun `#fromNameAndUrl handles http URL`() {
        // given
        val url = "http://api.example.com:8080"

        // when
        val cluster = Cluster.fromNameAndUrl(url)

        // then
        assertThat(cluster).isNotNull()
        assertThat(cluster?.url).isEqualTo(url)
    }

    @Test
    fun `#Cluster constructor creates instance with client certificate authentication`() {
        // given
        val name = "yavin"
        val url = "https://api.yavin.rebel"

        // when
        val cluster = Cluster(
            name = name,
            url = url,
            clientCert = CertificateSource.fromData("cert-data"),
            clientKey = CertificateSource.fromData("key-data")
        )

        // then
        assertThat(cluster.name).isEqualTo(name)
        assertThat(cluster.url).isEqualTo(url)
        assertThat(cluster.token).isNull()
        assertThat(cluster.clientCert?.value).isEqualTo("cert-data")
        assertThat(cluster.clientKey?.value).isEqualTo("key-data")
    }

    @Test
    fun `#Cluster constructor allows token-only authentication`() {
        // given
        // when
        val cluster = Cluster(
            name = "scarif",
            url = "https://api.scarif.empire",
            token = "empire-token"
        )

        // then
        assertThat(cluster.token).isEqualTo("empire-token")
        assertThat(cluster.clientCert).isNull()
        assertThat(cluster.clientKey).isNull()
    }

    @Test
    fun `#Cluster constructor fails when both token and client certificate are provided`() {
        // given
        // when
        assertThatThrownBy {
            Cluster(
                name = "mustafar",
                url = "https://api.mustafar.sith",
                token = "vader-token",
                clientCert = CertificateSource.fromData("cert"),
                clientKey = CertificateSource.fromData("key")
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `#Cluster constructor fails when certificate is provided without key`() {
        // given
        // when
        assertThatThrownBy {
            Cluster(
                name = "kamino",
                url = "https://api.kamino.cloners",
                clientCert = CertificateSource.fromData("cert-only")
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `#Cluster constructor fails when key is provided without certificate`() {
        // given
        // when
        assertThatThrownBy {
            Cluster(
                name = "geonosis",
                url = "https://api.geonosis.droids",
                clientKey = CertificateSource.fromData("key-only")
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `#equals and hashCode include client certificate fields`() {
        // given
        val cluster1 = Cluster(
            name = "endor",
            url = "https://api.endor.rebel",
            clientCert = CertificateSource.fromData("cert"),
            clientKey = CertificateSource.fromData("key")
        )
        val cluster2 = Cluster(
            name = "endor",
            url = "https://api.endor.rebel",
            clientCert = CertificateSource.fromData("cert"),
            clientKey = CertificateSource.fromData("key")
        )
        val cluster3 = Cluster(
            name = "endor",
            url = "https://api.endor.rebel",
            token = "ewok-token"
        )

        // when
        // then
        assertThat(cluster1).isEqualTo(cluster2)
        assertThat(cluster1.hashCode()).isEqualTo(cluster2.hashCode())
        assertThat(cluster1).isNotEqualTo(cluster3)
    }
}
