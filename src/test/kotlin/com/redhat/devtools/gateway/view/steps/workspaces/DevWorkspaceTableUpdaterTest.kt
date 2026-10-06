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
package com.redhat.devtools.gateway.view.steps.workspaces

import com.redhat.devtools.gateway.devworkspace.DevWorkspace
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceObjectMeta
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceSpec
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceStatus
import com.redhat.devtools.gateway.devworkspace.DevWorkspaces
import com.redhat.devtools.gateway.devworkspace.Templates
import com.redhat.devtools.gateway.devworkspace.WorkspaceEditorKind
import com.redhat.devtools.gateway.devworkspace.WorkspaceEditorResolver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevWorkspaceTableUpdaterTest {

    private lateinit var devWorkspaces: DevWorkspaces
    private lateinit var model: DevWorkspaceTableModel

    @BeforeEach
    fun setUp() {
        devWorkspaces = mockk(relaxed = true)
        model = DevWorkspaceTableModel()
    }

    @Test
    fun `onAdded inserts workspace with editor resolved from annotation`() = runTest {
        // given
        val updater = updater(this)
        val dw = workspace("w1", "ns", cheEditor = "eclipse/che-idea-server/latest")

        // when
        updater.onAdded(dw)

        // then
        assertThat(model.getRowCount()).isEqualTo(1)
        assertThat(model[0].workspace).isEqualTo(dw)
        assertThat(model[0].editor.kind).isEqualTo(WorkspaceEditorKind.INTELLIJ_IDEA)
    }

    @Test
    fun `onAdded inserts unknown workspace and triggers background template fetch`() = runTest {
        // given
        every { devWorkspaces.loadTemplates("ns") } returns Templates(emptyMap(), unavailable = true)
        val updater = updater(this)

        // when
        updater.onAdded(workspace("w1", "ns"))
        advanceUntilIdle()

        // then
        assertThat(model.getRowCount()).isEqualTo(1)
        assertThat(model[0].editor.kind).isEqualTo(WorkspaceEditorKind.UNKNOWN)
        verify { devWorkspaces.loadTemplates("ns") }
    }

    @Test
    fun `onAdded does not fetch when namespace is known to have no templates`() = runTest {
        // given
        val resolver = resolver(this)
        resolver.seedTemplateCache(emptyMap(), setOf("ns"))
        val updater = DevWorkspaceTableUpdater(model, resolver)

        // when
        updater.onAdded(workspace("w1", "ns"))
        advanceUntilIdle()

        // then
        assertThat(model.getRowCount()).isEqualTo(1)
        assertThat(model[0].editor.kind).isEqualTo(WorkspaceEditorKind.UNKNOWN)
        verify(exactly = 0) { devWorkspaces.loadTemplates("ns") }
    }

    @Test
    fun `onAdded keeps model sorted`() = runTest {
        // given
        every { devWorkspaces.loadTemplates("ns") } returns Templates(emptyMap(), unavailable = true)
        val updater = updater(this)

        // when
        updater.onAdded(workspace("b", "ns", cheEditor = "eclipse/che-idea-server/latest")) // JETBRAINS-family, prio 0
        advanceUntilIdle()
        updater.onAdded(workspace("a", "ns")) // UNKNOWN, prio 1
        advanceUntilIdle()

        // then
        assertThat(model.getRowCount()).isEqualTo(2)
        assertThat(model[0].workspace.name).isEqualTo("b")
        assertThat(model[1].workspace.name).isEqualTo("a")
    }

    @Test
    fun `onUpdated preserves previously resolved editor`() = runTest {
        // given
        every { devWorkspaces.loadTemplates("ns") } returns Templates(emptyMap(), unavailable = true)
        val updater = updater(this)
        updater.onAdded(workspace("w1", "ns", cheEditor = "eclipse/che-idea-server/latest"))
        advanceUntilIdle()
        assertThat(model[0].editor.kind).isEqualTo(WorkspaceEditorKind.INTELLIJ_IDEA)

        // when — same workspace identity (name+namespace), different phase, no annotation
        updater.onUpdated(workspace("w1", "ns", phase = "Failed"))

        // then — editor must not flip to UNKNOWN (CRW-11897)
        assertThat(model.getRowCount()).isEqualTo(1)
        assertThat(model[0].editor.kind).isEqualTo(WorkspaceEditorKind.INTELLIJ_IDEA)
        assertThat(model[0].workspace.phase).isEqualTo("Failed")
    }

    @Test
    fun `onUpdated resolves and inserts workspace missing from model`() = runTest {
        // given
        every { devWorkspaces.loadTemplates("ns") } returns Templates(emptyMap(), unavailable = true)
        val updater = updater(this)

        // when — workspace not in model (missed ADDED), no background fetch needed (annotation)
        updater.onUpdated(workspace("w1", "ns", cheEditor = "eclipse/che-idea-server/latest"))
        advanceUntilIdle()

        // then
        assertThat(model.getRowCount()).isEqualTo(1)
        assertThat(model[0].editor.kind).isEqualTo(WorkspaceEditorKind.INTELLIJ_IDEA)
    }

    @Test
    fun `onUpdated resolves and inserts unknown workspace missing from model`() = runTest {
        // given
        every { devWorkspaces.loadTemplates("ns") } returns Templates(emptyMap(), unavailable = true)
        val updater = updater(this)

        // when
        updater.onUpdated(workspace("w1", "ns"))
        advanceUntilIdle()

        // then
        assertThat(model.getRowCount()).isEqualTo(1)
        assertThat(model[0].editor.kind).isEqualTo(WorkspaceEditorKind.UNKNOWN)
    }

    @Test
    fun `onUpdated ignores an update with an older or equal resourceVersion`() = runTest {
        // given
        every { devWorkspaces.loadTemplates("ns") } returns Templates(emptyMap(), unavailable = true)
        val updater = updater(this)
        updater.onAdded(workspace("w1", "ns", phase = "Running", resourceVersion = "100"))
        advanceUntilIdle()

        // when: a stale (older) and then an equal resourceVersion both arrive with a
        // regressed phase — e.g. a relist served from a lagging apiserver replica (CRW-12992)
        updater.onUpdated(workspace("w1", "ns", phase = "Starting", resourceVersion = "50"))
        updater.onUpdated(workspace("w1", "ns", phase = "Starting", resourceVersion = "100"))

        // then: both are dropped, the newer state stays displayed
        assertThat(model.getRowCount()).isEqualTo(1)
        assertThat(model[0].workspace.phase).isEqualTo("Running")
    }

    @Test
    fun `onUpdated applies an update with a newer resourceVersion`() = runTest {
        // given
        every { devWorkspaces.loadTemplates("ns") } returns Templates(emptyMap(), unavailable = true)
        val updater = updater(this)
        updater.onAdded(workspace("w1", "ns", phase = "Running", resourceVersion = "100"))
        advanceUntilIdle()

        // when: a genuinely newer resourceVersion arrives — must be applied even though
        // its phase looks like a "regression"; we only guard on version ordering, never
        // on phase semantics, since a real server-side state change must never be hidden
        updater.onUpdated(workspace("w1", "ns", phase = "Starting", resourceVersion = "101"))

        // then
        assertThat(model.getRowCount()).isEqualTo(1)
        assertThat(model[0].workspace.phase).isEqualTo("Starting")
    }

    @Test
    fun `onUpdated applies an update when resourceVersion is missing or unparseable`() = runTest {
        // given: no resourceVersion populated at all (fail open — no behavior change
        // for any caller that doesn't set it)
        every { devWorkspaces.loadTemplates("ns") } returns Templates(emptyMap(), unavailable = true)
        val updater = updater(this)
        updater.onAdded(workspace("w1", "ns", phase = "Running"))
        advanceUntilIdle()

        // when
        updater.onUpdated(workspace("w1", "ns", phase = "Starting"))

        // then
        assertThat(model.getRowCount()).isEqualTo(1)
        assertThat(model[0].workspace.phase).isEqualTo("Starting")
    }

    @Test
    fun `onDeleted removes workspace from model`() = runTest {
        // given
        every { devWorkspaces.loadTemplates("ns") } returns Templates(emptyMap(), unavailable = true)
        val updater = updater(this)
        updater.onAdded(workspace("w1", "ns", cheEditor = "eclipse/che-idea-server/latest"))
        advanceUntilIdle()
        assertThat(model.getRowCount()).isEqualTo(1)

        // when
        updater.onDeleted(workspace("w1", "ns"))

        // then
        assertThat(model.getRowCount()).isZero()
    }

    @Test
    fun `onReset removes workspaces no longer present and upserts the rest`() = runTest {
        // given
        every { devWorkspaces.loadTemplates("ns") } returns Templates(emptyMap(), unavailable = true)
        val updater = updater(this)
        updater.onAdded(workspace("stale", "ns"))
        updater.onAdded(workspace("w1", "ns", cheEditor = "eclipse/che-idea-server/latest"))
        advanceUntilIdle()
        assertThat(model.getRowCount()).isEqualTo(2)

        // when: relist no longer reports "stale", reports "w1" with a new phase (no annotation
        // this time — editor resolution for an update must not depend on it, see CRW-11897),
        // and a brand-new "w2" created outside the watch.
        updater.onReset(
            "ns",
            listOf(
                workspace("w1", "ns", phase = "Running"),
                workspace("w2", "ns")
            )
        )
        advanceUntilIdle()

        // then
        assertThat(model.getRowCount()).isEqualTo(2)
        val names = (0 until model.getRowCount()).map { model[it].workspace.name }
        assertThat(names).containsExactlyInAnyOrder("w1", "w2")
        val w1 = (0 until model.getRowCount()).first { model[it].workspace.name == "w1" }
        assertThat(model[w1].workspace.phase).isEqualTo("Running")
        assertThat(model[w1].editor.kind).isEqualTo(WorkspaceEditorKind.INTELLIJ_IDEA) // preserved, not flipped
    }

    @Test
    fun `onReset leaves other namespaces untouched`() = runTest {
        // given
        every { devWorkspaces.loadTemplates(any()) } returns Templates(emptyMap(), unavailable = true)
        val updater = updater(this)
        updater.onAdded(workspace("other-ns-ws", "other-ns"))
        advanceUntilIdle()
        assertThat(model.getRowCount()).isEqualTo(1)

        // when: relisting an unrelated namespace ("ns") must not touch "other-ns" rows
        updater.onReset("ns", emptyList())
        advanceUntilIdle()

        // then
        assertThat(model.getRowCount()).isEqualTo(1)
        assertThat(model[0].workspace.namespace).isEqualTo("other-ns")
    }

    private fun resolver(scope: TestScope): WorkspaceEditorResolver {
        return WorkspaceEditorResolver(
            devWorkspaces = devWorkspaces,
            scope = scope,
            onEditorResolved = { _ -> },
            dispatchEdt = { it() }
        )
    }

    private fun updater(scope: TestScope): DevWorkspaceTableUpdater {
        return DevWorkspaceTableUpdater(model, resolver(scope))
    }

    private fun workspace(
        name: String,
        namespace: String,
        phase: String = "Running",
        cheEditor: String? = null,
        resourceVersion: String? = null
    ): DevWorkspace {
        return DevWorkspace(
            DevWorkspaceObjectMeta(
                name = name,
                namespace = namespace,
                uid = "$namespace/$name",
                annotations = if (cheEditor != null) mapOf("che.eclipse.org/che-editor" to cheEditor) else emptyMap(),
                labels = emptyMap(),
                resourceVersion = resourceVersion
            ),
            DevWorkspaceSpec(started = true),
            DevWorkspaceStatus(phase = phase)
        )
    }
}