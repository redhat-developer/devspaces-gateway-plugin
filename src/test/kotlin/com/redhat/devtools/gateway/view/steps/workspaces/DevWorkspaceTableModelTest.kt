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
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceListItem
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceObjectMeta
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceSpec
import com.redhat.devtools.gateway.devworkspace.DevWorkspaceStatus
import com.redhat.devtools.gateway.devworkspace.WorkspaceEditorInfo
import com.redhat.devtools.gateway.devworkspace.WorkspaceEditorKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DevWorkspaceTableModelTest {

    @Test
    fun `addAll keeps items sorted by editor priority then namespace then name`() {
        // given
        val model = DevWorkspaceTableModel()
        model.addAll(
            listOf(
                item("a", "ns1", WorkspaceEditorKind.UNKNOWN),
                item("b", "ns1", WorkspaceEditorKind.JETBRAINS),
                item("a", "ns0", WorkspaceEditorKind.UNKNOWN),
                item("c", "ns2", WorkspaceEditorKind.VSCODE),
                item("a", "ns1", WorkspaceEditorKind.JETBRAINS)
            )
        )

        // then
        assertThat(model.getRowCount()).isEqualTo(5)
        assertThat(names(model)).containsExactly("a", "b", "a", "a", "c")
        assertThat(namespaces(model)).containsExactly("ns1", "ns1", "ns0", "ns1", "ns2")
        assertThat(kinds(model)).containsExactly(
            WorkspaceEditorKind.JETBRAINS,
            WorkspaceEditorKind.JETBRAINS,
            WorkspaceEditorKind.UNKNOWN,
            WorkspaceEditorKind.UNKNOWN,
            WorkspaceEditorKind.VSCODE
        )
    }

    @Test
    fun `add inserts item at given index`() {
        // given
        val model = DevWorkspaceTableModel()
        model.addAll(listOf(item("a"), item("c")))

        // when
        model.add(1, item("b"))

        // then
        assertThat(names(model)).containsExactly("a", "b", "c")
    }

    @Test
    fun `set replaces item at index and keeps row count`() {
        // given
        val model = DevWorkspaceTableModel()
        model.addAll(listOf(item("a"), item("b")))

        // when
        model.set(0, item("z"))

        // then — sort key changed, row repositioned to keep the table sorted
        assertThat(model.getRowCount()).isEqualTo(2)
        assertThat(names(model)).containsExactly("b", "z")
    }

    @Test
    fun `set with unchanged sort key updates row in place`() {
        // given
        val model = DevWorkspaceTableModel()
        model.addAll(listOf(item("a"), item("b")))

        // when
        model.set(0, item("a", namespace = "ns", editor = WorkspaceEditorKind.UNKNOWN))

        // then — sort key identical, no repositioning
        assertThat(names(model)).containsExactly("a", "b")
    }

    @Test
    fun `remove deletes item at index`() {
        // given
        val model = DevWorkspaceTableModel()
        model.addAll(listOf(item("a"), item("b"), item("c")))

        // when
        model.remove(1)

        // then
        assertThat(names(model)).containsExactly("a", "c")
    }

    @Test
    fun `clear removes all rows`() {
        // given
        val model = DevWorkspaceTableModel()
        model.addAll(listOf(item("a"), item("b")))

        // when
        model.clear()

        // then
        assertThat(model.getRowCount()).isZero()
    }

    @Test
    fun `indexOfFirst returns index of matching item`() {
        // given
        val model = DevWorkspaceTableModel()
        model.addAll(listOf(item("a"), item("b")))

        // when
        val index = model.indexOfFirst { it.workspace.name == "b" }

        // then
        assertThat(index).isEqualTo(1)
    }

    @Test
    fun `indexOfFirst returns minus one when nothing matches`() {
        // given
        val model = DevWorkspaceTableModel()
        model.addAll(listOf(item("a")))

        // when
        val index = model.indexOfFirst { it.workspace.name == "missing" }

        // then
        assertThat(index).isEqualTo(-1)
    }

    @Test
    fun `getValueAt returns the item for any cell of a row`() {
        // given
        val model = DevWorkspaceTableModel()
        val expected = item("a")
        model.addAll(listOf(expected))

        // when/then
        assertThat(model.getValueAt(0, 0)).isSameAs(expected)
        assertThat(model.getValueAt(0, 3)).isSameAs(expected)
    }

    @Test
    fun `getColumnCount is four`() {
        assertThat(DevWorkspaceTableModel().getColumnCount()).isEqualTo(4)
    }

    private fun names(model: DevWorkspaceTableModel): List<String> =
        (0 until model.getRowCount()).map { model[it].workspace.name }

    private fun namespaces(model: DevWorkspaceTableModel): List<String> =
        (0 until model.getRowCount()).map { model[it].workspace.namespace }

    private fun kinds(model: DevWorkspaceTableModel): List<WorkspaceEditorKind> =
        (0 until model.getRowCount()).map { model[it].editor.kind }

    private fun item(
        name: String,
        namespace: String = "ns",
        editor: WorkspaceEditorKind = WorkspaceEditorKind.UNKNOWN
    ): DevWorkspaceListItem {
        return DevWorkspaceListItem(
            workspace = DevWorkspace(
                DevWorkspaceObjectMeta(
                    name = name,
                    namespace = namespace,
                    uid = "$namespace/$name",
                    annotations = emptyMap(),
                    labels = emptyMap()
                ),
                DevWorkspaceSpec(started = true),
                DevWorkspaceStatus(phase = "Running")
            ),
            editor = WorkspaceEditorInfo(editor, editor.name)
        )
    }
}