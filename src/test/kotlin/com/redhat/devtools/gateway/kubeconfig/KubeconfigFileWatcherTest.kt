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
package com.redhat.devtools.gateway.kubeconfig

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.writeText

@ExperimentalCoroutinesApi
class KubeconfigFileWatcherTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testFile: Path
    private lateinit var mockCallback: (Path) -> Unit

    @BeforeEach
    fun setup() {
        testFile = tempDir.resolve("test-kubeconfig.yaml")
        testFile.createFile()
        testFile.writeText("initial content")
        mockCallback = mockk(relaxed = true)
    }

    @Test
    fun `should invoke callback when adding a file`() = runTest {
        //given
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val watcher = KubeconfigFileWatcher(this, testDispatcher)
        watcher.onFileChanged { mockCallback }

        //when
        watcher.addFile(testFile)
        advanceUntilIdle()

        //then - Initial notification when file is added
        verify(exactly = 1) { mockCallback.invoke(testFile) }
        watcher.stop()
    }

    @Test
    fun `should not invoke callback for non-existent files`() = runTest {
        //given
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val watcher = KubeconfigFileWatcher(this, testDispatcher)
        watcher.onFileChanged { mockCallback }
        val nonExistentFile = tempDir.resolve("non-existent.yaml")

        //when
        watcher.addFile(nonExistentFile)
        advanceUntilIdle()

        //then - No callback should be invoked for non-existent files
        verify(exactly = 0) { mockCallback.invoke(nonExistentFile) }
        watcher.stop()
    }

    @Test
    fun `should invoke callback when adding multiple files`() = runTest {
        //given
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val watcher = KubeconfigFileWatcher(this, testDispatcher)
        watcher.onFileChanged { mockCallback }
        val secondFile = tempDir.resolve("second-file.yaml")
        secondFile.createFile()
        secondFile.writeText("second content")

        //when
        watcher.addFile(testFile)
        watcher.addFile(secondFile)
        advanceUntilIdle()

        //then - Callback should be invoked for both files
        verify(exactly = 1) { mockCallback.invoke(testFile) }
        verify(exactly = 1) { mockCallback.invoke(secondFile) }
        watcher.stop()
    }
}