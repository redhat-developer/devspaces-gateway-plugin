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

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.*

class ClusterTest {

    @Test
    fun `#Cluster constructor creates instance with name, url, and token`() {
        // given
        val name = "death-star"
        val url = "https://api.deathstar.empire"
        val token = "empire-token-4ls"
        
        // when
        val cluster = Cluster(name, url, token)
        
        // then
        assertThat(cluster.name)
            .isEqualTo(name)
        assertThat(cluster.url)
            .isEqualTo(url)
        assertThat(cluster.token)
            .isEqualTo(token)
        assertThat(cluster.id)
            .isEqualTo("death-star@api.deathstar.empire")
    }

    @Test
    fun `#Cluster constructor creates instance with default null token`() {
        // given
        val name = "tatooine"
        val url = "https://api.tatooine.galaxy"
        
        // when
        val cluster = Cluster(name, url)
        
        // then
        assertThat(cluster.name)
            .isEqualTo(name)
        assertThat(cluster.url)
            .isEqualTo(url)
        assertThat(cluster.token)
            .isNull()
        assertThat(cluster.id)
            .isEqualTo("tatooine@api.tatooine.galaxy")
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
        assertThat(cluster.toString())
            .isEqualTo("millennium-falcon (https://api.falcon.ship)")
    }

    @Test
    fun `#id property returns formatted id removing protocol`() {
        val cluster1 = Cluster("x-wing", "https://api.xwing.rebel")
        assertThat(cluster1.id)
            .isEqualTo("x-wing@api.xwing.rebel")

        val cluster2 = Cluster("tie-fighter", "http://api.tie.empire")
        assertThat(cluster2.id)
            .isEqualTo("tie-fighter@api.tie.empire")

        val cluster3 = Cluster("star-destroyer", "https://api.destroyer.empire:8443")
        assertThat(cluster3.id)
            .isEqualTo("star-destroyer@api.destroyer.empire:8443")

        val cluster4 = Cluster("jedi-council", "https://api.jedi.temple/path")
        assertThat(cluster4.id)
            .isEqualTo("jedi-council@api.jedi.temple/path")
    }

    @Test
    fun `#equals returns true for clusters with same properties`() {
        // given
        val cluster1 = Cluster("r2d2", "https://api.robots.galaxy", "droid-token-1")
        val cluster2 = Cluster("r2d2", "https://api.robots.galaxy", "droid-token-1")
        val cluster3 = Cluster("c3po", "https://api.robots.galaxy", "droid-token-1")
        
        // when & then
        assertThat(cluster1)
            .isEqualTo(cluster2)
        assertThat(cluster1)
            .isNotEqualTo(cluster3)
    }

    @Test
    fun `#hashCode returns same value for clusters with same properties`() {
        // given
        val cluster1 = Cluster("r2d2", "https://api.robots.galaxy", "droid-token-1")
        val cluster2 = Cluster("r2d2", "https://api.robots.galaxy", "droid-token-1")
        
        // when & then
        assertThat(cluster1.hashCode())
            .isEqualTo(cluster2.hashCode())
    }

    @Test
    fun `#copy method creates new instance with modified properties`() {
        // given
        val original = Cluster("obi-wan", "https://api.kenobi.jedi", "kenobi-token-1")
        
        // when
        val copy = original.copy(name = "ben-kenobi")
        
        // then
        assertThat(copy.name)
            .isEqualTo("ben-kenobi")
        assertThat(copy.url)
            .isEqualTo("https://api.kenobi.jedi")
        assertThat(copy.token)
            .isEqualTo("kenobi-token-1")
    }

    @Test
    fun `#name returns url without scheme nor path`() {
        val cluster1 = Cluster.fromNameAndUrl("https://jedi-temple.galaxy")
        assertThat(cluster1?.name)
            .isEqualTo("jedi-temple.galaxy")

        val cluster2 = Cluster.fromNameAndUrl("http://local-transport:8080")
        assertThat(cluster2?.name)
            .isEqualTo("local-transport-8080")

        val cluster3 = Cluster.fromNameAndUrl("https://rebel-base.galaxy:443/")
        assertThat(cluster3?.name)
            .isEqualTo("rebel-base.galaxy-443")

        val cluster4 = Cluster.fromNameAndUrl("https://sith-tower.galaxy:9090/api/v1")
        assertThat(cluster4?.name)
            .isEqualTo("sith-tower.galaxy-9090")
    }

    @Test
    fun `#id returns name@url without scheme, port nor path`() {
        val cluster1 = Cluster("x-wing", "https://api.xwing.rebel")
        assertThat(cluster1.id)
            .isEqualTo("x-wing@api.xwing.rebel")

        val cluster2 = Cluster("tie-fighter", "http://localhost:8080")
        assertThat(cluster2.id)
            .isEqualTo("tie-fighter@localhost:8080")

        val cluster3 = Cluster("star-destroyer", "https://api.destroyer.empire:443/")
        assertThat(cluster3.id)
            .isEqualTo("star-destroyer@api.destroyer.empire:443/")

        val cluster4 = Cluster("jedi-council", "https://api.jedi.temple:9090/api/v1")
        assertThat(cluster4.id)
            .isEqualTo("jedi-council@api.jedi.temple:9090/api/v1")
    }

    @Test
    fun `#fromNameAndUrl creates cluster from URL-only input`() {
        // given
        val url = "https://api.che-dev.x6e0.p1.openshiftapps.com:6443"

        // when
        val cluster = Cluster.fromNameAndUrl(url)

        // then
        assertThat(cluster)
            .isNotNull()
        assertThat(cluster?.url)
            .isEqualTo(url)
        assertThat(cluster?.name)
            .isNotNull()
            .isNotEmpty()
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
        assertThat(cluster)
            .isNotNull()
        assertThat(cluster?.name)
            .isEqualTo(name)
        assertThat(cluster?.url)
            .isEqualTo(url)
    }

    @Test
    fun `#fromNameAndUrl handles http URL`() {
        // given
        val url = "http://api.example.com:8080"

        // when
        val cluster = Cluster.fromNameAndUrl(url)

        // then
        assertThat(cluster)
            .isNotNull()
        assertThat(cluster?.url)
            .isEqualTo(url)
    }
}
