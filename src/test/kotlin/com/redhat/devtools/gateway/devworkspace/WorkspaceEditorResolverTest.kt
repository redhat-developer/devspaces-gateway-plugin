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

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceEditorResolverTest {

    private lateinit var devWorkspaces: DevWorkspaces
    private val resolvedEditors = mutableListOf<Pair<DevWorkspace, WorkspaceEditorInfo>>()
    private val dispatchEdt: (() -> Unit) -> Unit = { it() }

    @BeforeEach
    fun setUp() {
        devWorkspaces = mockk(relaxed = true)
        resolvedEditors.clear()
    }

    @Test
    fun `resolve returns JETBRAINS when templates are seeded for workspace uid`() {
        // given
        val dw = workspace("w1", "ns", uid = "uid1")
        val resolver = resolver(CoroutineScope(Dispatchers.Unconfined))
        resolver.seedTemplateCache(
            mapOf("ns" to mapOf("uid1" to listOf(jetbrainsTemplate("ns", "uid1")))),
            emptySet()
        )

        // when
        val info = resolver.resolve(dw)

        // then
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.JETBRAINS)
    }

    @Test
    fun `resolve returns UNKNOWN when no templates are cached`() {
        // given
        val resolver = resolver(CoroutineScope(Dispatchers.Unconfined))

        // when
        val info = resolver.resolve(workspace("w1", "ns", uid = "uid1"))

        // then
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.UNKNOWN)
    }

    @Test
    fun `resolve uses che-editor annotation when no templates are cached`() {
        // given
        val resolver = resolver(CoroutineScope(Dispatchers.Unconfined))

        // when
        val info = resolver.resolve(workspace("w1", "ns", cheEditor = "eclipse/che-idea-server/latest"))

        // then
        assertThat(info.kind).isEqualTo(WorkspaceEditorKind.INTELLIJ_IDEA)
    }

    @Test
    fun `seedTemplateCache populates unavailable namespaces`() {
        // given
        val resolver = resolver(CoroutineScope(Dispatchers.Unconfined))

        // when
        resolver.seedTemplateCache(emptyMap(), setOf("ns1"))

        // then
        assertThat(resolver.templatesUnavailable("ns1")).isTrue()
        assertThat(resolver.templatesUnavailable("ns2")).isFalse()
    }

    @Test
    fun `background fetch coalesces concurrent fetches per namespace`() {
        // given — the first fetch blocks inside loadTemplates so the second stays in flight
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loadCalls = AtomicInteger()
        every { devWorkspaces.loadTemplates("ns") } answers {
            loadCalls.incrementAndGet()
            started.countDown()
            release.await()
            Templates(emptyMap(), unavailable = true)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val resolver = resolver(scope)
            val dw = workspace("w1", "ns", uid = "uid1")

            // when — second fetch arrives while the first is still in flight
            resolver.backgroundFetchTemplatesAndPatch(dw)
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue()
            resolver.backgroundFetchTemplatesAndPatch(dw)

            // then
            assertThat(loadCalls.get()).isEqualTo(1)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `background fetch marks namespace unavailable and skips callback when templates fail`() = runTest {
        // given
        every { devWorkspaces.loadTemplates("ns") } returns Templates(emptyMap(), unavailable = true)
        val resolver = resolver(this)

        // when
        resolver.backgroundFetchTemplatesAndPatch(workspace("w1", "ns", uid = "uid1"))
        advanceUntilIdle()

        // then
        assertThat(resolver.templatesUnavailable("ns")).isTrue()
        assertThat(resolvedEditors).isEmpty()
    }

    @Test
    fun `background fetch updates cache and dispatches resolved editor`() = runTest {
        // given
        every { devWorkspaces.loadTemplates("ns") } returns Templates(
            mapOf("uid1" to listOf(jetbrainsTemplate("ns", "uid1"))),
            unavailable = false
        )
        val resolver = resolver(this)
        val dw = workspace("w1", "ns", uid = "uid1")

        // when
        resolver.backgroundFetchTemplatesAndPatch(dw)
        advanceUntilIdle()

        // then
        assertThat(resolver.resolve(dw).kind).isEqualTo(WorkspaceEditorKind.JETBRAINS)
        assertThat(resolver.templatesUnavailable("ns")).isFalse()
        assertThat(resolvedEditors).hasSize(1)
        assertThat(resolvedEditors.single().first).isEqualTo(dw)
        assertThat(resolvedEditors.single().second.kind).isEqualTo(WorkspaceEditorKind.JETBRAINS)
    }

    @Test
    fun `background fetch updates cache but does not dispatch when editor stays unknown`() = runTest {
        // given
        // Templates are available but none match the workspace uid -> editor stays UNKNOWN.
        every { devWorkspaces.loadTemplates("ns") } returns Templates(
            mapOf("other-uid" to listOf(jetbrainsTemplate("ns", "other-uid"))),
            unavailable = false
        )
        val resolver = resolver(this)
        val dw = workspace("w1", "ns", uid = "uid1")

        // when
        resolver.backgroundFetchTemplatesAndPatch(dw)
        advanceUntilIdle()

        // then
        assertThat(resolver.resolve(dw).kind).isEqualTo(WorkspaceEditorKind.UNKNOWN)
        assertThat(resolver.templatesUnavailable("ns")).isFalse()
        assertThat(resolvedEditors).isEmpty()
    }

    @Test
    fun `background fetch patches other tracked workspaces that now resolve`() = runTest {
        // given — w1 triggers the fetch; w2 (same namespace) was resolved earlier as UNKNOWN
        every { devWorkspaces.loadTemplates("ns") } returns Templates(
            mapOf(
                "uid1" to listOf(jetbrainsTemplate("ns", "uid1")),
                "uid2" to listOf(jetbrainsTemplate("ns", "uid2"))
            ),
            unavailable = false
        )
        val resolver = resolver(this)
        val dw1 = workspace("w1", "ns", uid = "uid1")
        val dw2 = workspace("w2", "ns", uid = "uid2")
        assertThat(resolver.resolve(dw1).kind).isEqualTo(WorkspaceEditorKind.UNKNOWN)
        assertThat(resolver.resolve(dw2).kind).isEqualTo(WorkspaceEditorKind.UNKNOWN)

        // when
        resolver.backgroundFetchTemplatesAndPatch(dw1)
        advanceUntilIdle()

        // then — both the triggering workspace and the coalesced one are patched
        assertThat(resolvedEditors.map { it.first })
            .containsExactlyInAnyOrder(dw1, dw2)
        assertThat(resolvedEditors.map { it.second.kind })
            .containsExactlyInAnyOrder(
                WorkspaceEditorKind.JETBRAINS,
                WorkspaceEditorKind.JETBRAINS
            )
        resolver.untrack(dw2)
    }

    @Test
    fun `background fetch does not patch workspaces in other namespaces`() = runTest {
        // given
        every { devWorkspaces.loadTemplates("ns") } returns Templates(
            mapOf("uid1" to listOf(jetbrainsTemplate("ns", "uid1"))),
            unavailable = false
        )
        val resolver = resolver(this)
        val dw1 = workspace("w1", "ns", uid = "uid1")
        val dwOther = workspace("w2", "other-ns", uid = "other-ns/w2")
        assertThat(resolver.resolve(dw1).kind).isEqualTo(WorkspaceEditorKind.UNKNOWN)
        assertThat(resolver.resolve(dwOther).kind).isEqualTo(WorkspaceEditorKind.UNKNOWN)

        // when
        resolver.backgroundFetchTemplatesAndPatch(dw1)
        advanceUntilIdle()

        // then
        assertThat(resolvedEditors.map { it.first }).containsExactly(dw1)
    }

    @Test
    fun `cancelInFlight is safe when nothing is in flight`() = runTest {
        // given
        val resolver = resolver(this)

        // when/then
        resolver.cancelInFlight()
        assertThat(resolver.templatesUnavailable("ns")).isFalse()
    }

    @Test
    fun `background fetch uses latest tracked workspace after refresh`() {
        // given — block loadTemplates so we can refresh before the fetch completes
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { devWorkspaces.loadTemplates("ns") } answers {
            started.countDown()
            release.await()
            Templates(
                mapOf("uid1" to listOf(jetbrainsTemplate("ns", "uid1"))),
                unavailable = false
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val resolver = resolver(scope)
            val dwStarting = workspace("w1", "ns", uid = "uid1", phase = "Starting")
            val dwRunning = workspace("w1", "ns", uid = "uid1", phase = "Running")

            // when — resolve starting phase, trigger background fetch, then refresh to running
            resolver.resolve(dwStarting)
            resolver.backgroundFetchTemplatesAndPatch(dwStarting)
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue()
            resolver.refreshTracked(dwRunning)
            release.countDown()
            Thread.sleep(200)

            // then — the dispatched patch should use the refreshed Running phase workspace
            assertThat(resolvedEditors).hasSize(1)
            assertThat(resolvedEditors.single().first.phase).isEqualTo("Running")
            assertThat(resolvedEditors.single().first).isEqualTo(dwRunning)
            assertThat(resolvedEditors.single().second.kind).isEqualTo(WorkspaceEditorKind.JETBRAINS)
        } finally {
            scope.cancel()
        }
    }

    private fun resolver(scope: CoroutineScope): WorkspaceEditorResolver {
        return WorkspaceEditorResolver(
            devWorkspaces = devWorkspaces,
            scope = scope,
            onEditorResolved = { patches -> resolvedEditors += patches },
            dispatchEdt = dispatchEdt
        )
    }

    private fun workspace(
        name: String,
        namespace: String,
        uid: String = "$namespace/$name",
        cheEditor: String? = null,
        phase: String = "Running"
    ): DevWorkspace {
        return DevWorkspace(
            DevWorkspaceObjectMeta(
                name = name,
                namespace = namespace,
                uid = uid,
                annotations = if (cheEditor != null) mapOf("che.eclipse.org/che-editor" to cheEditor) else emptyMap(),
                labels = emptyMap()
            ),
            DevWorkspaceSpec(started = true),
            DevWorkspaceStatus(phase = phase)
        )
    }

    private fun jetbrainsTemplate(namespace: String, ownerUid: String): DevWorkspaceTemplate {
        return DevWorkspaceTemplate(
            metadata = DevWorkspaceTemplateMetadata(
                name = "jetbrains-template",
                namespace = namespace,
                pluginRegistryUrl = null,
                ownerRefencesUids = listOf(ownerUid)
            ),
            spec = DevWorkspaceTemplateSpec(components = listOf(mapOf("volume" to mapOf("name" to "idea-server"))))
        )
    }
}