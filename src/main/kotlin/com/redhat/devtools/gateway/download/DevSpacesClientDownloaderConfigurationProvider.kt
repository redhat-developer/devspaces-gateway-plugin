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

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.remoteDev.downloader.JetBrainsClientDownloaderConfigurationProvider
import com.intellij.remoteDev.downloader.RealJetBrainsClientDownloaderConfigurationProvider
import com.jetbrains.rd.util.reactive.Signal
import java.net.URI
import java.nio.file.Path

/**
 * Overrides the platform's [JetBrainsClientDownloaderConfigurationProvider] (declared in
 * `plugin.xml` with `overrides="true"`).
 *
 * While a Dev Spaces connection is being established and the JetBrains Client was
 * mirrored locally (see [DevSpacesClientMirror]), the download base URLs point into the mirror as
 * `file:` URLs. The platform's `CodeWithMeClientDownloader` natively supports `file:`
 * URLs (plain `Files.copy`), so checksum verification, signature verification and
 * extraction keep working unchanged - only the network transfer is replaced by a local
 * copy. See [ResumableFileDownloader] for why the network transfer is replaced at all.
 *
 * Outside of a Dev Spaces connect everything delegates to the platform default
 * ([RealJetBrainsClientDownloaderConfigurationProvider]).
 */
@Suppress("UnstableApiUsage")
class DevSpacesClientDownloaderConfigurationProvider : JetBrainsClientDownloaderConfigurationProvider {

    private val delegate = RealJetBrainsClientDownloaderConfigurationProvider()

    override val clientDownloadUrl: URI
        get() = DevSpacesClientMirrorOverride.activeMirrorDir?.toUri()
            ?: delegate.clientDownloadUrl

    override val jreDownloadUrl: URI
        get() = DevSpacesClientMirrorOverride.activeMirrorDir?.toUri()
            ?: delegate.jreDownloadUrl

    override val clientCachesDir: Path
        get() = delegate.clientCachesDir

    override val clientVersionManagementEnabled: Boolean
        get() = delegate.clientVersionManagementEnabled

    override val modifiedDateInManifestIncluded: Boolean
        get() = delegate.modifiedDateInManifestIncluded

    override val verifySignature: Boolean
        get() = delegate.verifySignature

    override val clientLaunched: Signal<Unit>
        get() = delegate.clientLaunched

    override val downloadLatestBuildFromCDNForSnapshotHost: Boolean
        get() = delegate.downloadLatestBuildFromCDNForSnapshotHost

    override fun modifyClientCommandLine(clientCommandLine: GeneralCommandLine) =
        delegate.modifyClientCommandLine(clientCommandLine)

    override fun patchVmOptions(vmOptionsFile: Path, connectionUri: URI) =
        delegate.patchVmOptions(vmOptionsFile, connectionUri)
}
