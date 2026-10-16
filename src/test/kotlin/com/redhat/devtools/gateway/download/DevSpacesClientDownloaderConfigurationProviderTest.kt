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
package com.redhat.devtools.gateway.download

import com.intellij.openapi.application.ApplicationManager
import com.intellij.remoteDev.downloader.JetBrainsClientDownloaderConfigurationProvider
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Boots the platform with the plugin installed (see `plugins-test` sandbox) and verifies
 * that [DevSpacesClientDownloaderConfigurationProvider] really overrides the platform's
 * `JetBrainsClientDownloaderConfigurationProvider` application service, and that it only
 * redirects the download URLs while a client mirror is active.
 */
@Suppress("UnstableApiUsage")
@TestApplication
class DevSpacesClientDownloaderConfigurationProviderTest {

    @JvmField
    @TempDir
    var tempDir: Path? = null

    private val provider: DevSpacesClientDownloaderConfigurationProvider
        get() = ApplicationManager.getApplication()
            .getService(JetBrainsClientDownloaderConfigurationProvider::class.java)
            as DevSpacesClientDownloaderConfigurationProvider

    @AfterEach
    fun afterEach() {
        DevSpacesClientMirrorOverride.resetForTest()
    }

    @Test
    fun `overrides the platform service`() {
        // when: the platform resolves the application service
        val service = ApplicationManager.getApplication()
            .getService(JetBrainsClientDownloaderConfigurationProvider::class.java)

        // then: the plugin's provider wins over the platform default
        check(service is DevSpacesClientDownloaderConfigurationProvider) {
            "the plugin must override JetBrainsClientDownloaderConfigurationProvider but got $service"
        }
    }

    @Test
    fun `serves mirror urls only while a mirror is active`() {
        // given: the default (CDN) urls without an active mirror
        val defaultClientUrl = provider.clientDownloadUrl
        val defaultJreUrl = provider.jreDownloadUrl

        // when: a client mirror is activated
        val activation = DevSpacesClientMirrorOverride.activate(tempDir!!)
        try {
            // then: the provider serves the mirror urls while the activation is open
            check(provider.clientDownloadUrl == tempDir!!.toUri()) {
                "expected mirror url but got ${provider.clientDownloadUrl}"
            }
            check(provider.jreDownloadUrl == tempDir!!.toUri()) {
                "expected mirror url but got ${provider.jreDownloadUrl}"
            }
        } finally {
            activation.close()
        }

        // then: the provider serves the default urls again once the mirror is inactive
        check(provider.clientDownloadUrl == defaultClientUrl)
        check(provider.jreDownloadUrl == defaultJreUrl)
    }

    @Test
    fun `default urls point to the jetbrains cdn`() {
        // given, when
        val clientDownloadUrl = provider.clientDownloadUrl.toString()
        val jreDownloadUrl = provider.jreDownloadUrl.toString()
        // then
        check(clientDownloadUrl == "https://download.jetbrains.com/idea/code-with-me/") {
            "unexpected default client url ${provider.clientDownloadUrl}"
        }
        // then
        check(jreDownloadUrl == "https://download.jetbrains.com/idea/jbr/") {
            "unexpected default jre url ${provider.jreDownloadUrl}"
        }
    }
}
