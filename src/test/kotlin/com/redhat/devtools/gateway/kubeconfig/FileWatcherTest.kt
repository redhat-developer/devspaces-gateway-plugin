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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout


import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.writeText

@ExperimentalCoroutinesApi
class FileWatcherTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testFile: Path
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope
    private lateinit var watcher: FileWatcher

    @BeforeEach
    fun beforeEach() {
        testFile = createFile("test-kubeconfig.yaml", "initial content")
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        watcher = FileWatcher(testScope, testDispatcher)
    }

    @AfterEach
    fun afterEach() {
        watcher.stop()
    }

    @Test
    fun `#addFile() invokes callback when a file is added`() = runTest {
        // given
        var onFileChangedCount = 0
        watcher.onFileChanged { onFileChangedCount++ }

        // when
        watcher.addFile(testFile)
        advanceUntilIdle()

        // then - Initial notification when file is added
        assertThat(onFileChangedCount).isEqualTo(1)
    }

    @Test
    fun `#addFile() does not invoke callback for non-existent files`() = runTest {
        // given
        var onFileChangedCount = 0
        watcher.onFileChanged { onFileChangedCount++ }
        val nonExistentFile = tempDir.resolve("non-existent.yaml")

        // when
        watcher.addFile(nonExistentFile)
        advanceUntilIdle()

        // then - No callback should be invoked for non-existent files
        assertThat(onFileChangedCount).isEqualTo(0)
    }

    @Test
    fun `#addFile() invokes callback when multiple files are added`() = runTest {
        // given
        var onFileChangedCount = 0
        watcher.onFileChanged { onFileChangedCount++ }
        val secondFile = createFile("second-file.yaml","second content")

        // when
        watcher
            .addFile(testFile)
            .addFile(secondFile)
        advanceUntilIdle()

        // then - Callback should be invoked for both files
        assertThat(onFileChangedCount).isEqualTo(2)
    }

    @Test
    fun `#removeFile() removes file from monitoring`() = runTest {
        // given
        var onFileChangedCount = 0
        watcher.onFileChanged { onFileChangedCount++ }

        // when - add file and verify it triggers callback
        watcher.addFile(testFile)
        advanceUntilIdle()
        assertThat(onFileChangedCount).isEqualTo(1)

        // when - remove file from monitoring
        watcher.removeFile(testFile)
        advanceUntilIdle()

        val initialOnFileChangedCount = onFileChangedCount
        
        // Re-adding the same file should trigger callback again since it was removed
        watcher.addFile(testFile)
        advanceUntilIdle()
        
        assertThat(onFileChangedCount).isEqualTo(initialOnFileChangedCount + 1)
    }

    @Test
    fun `#removeFile() handles non-existent files gracefully`() = runTest {
        // given
        val nonExistentFile = tempDir.resolve("never-added.yaml")

        // when - removing a file that was never added
        val result = watcher.removeFile(nonExistentFile)

        // then - should return the watcher instance and not throw any exceptions
        assertThat(result).isSameAs(watcher)
    }

    @Test
    fun `#removeFile() keeps other files monitored`() = runTest {
        // given
        val secondFile = createFile("second-file.yaml", "second content")
        val thirdFile = createFile("third-file.yaml", "third content")
        var onFileChangedCount = 0
        watcher.onFileChanged { onFileChangedCount++ }

        // when - add multiple files
        watcher
            .addFile(testFile)
            .addFile(secondFile)
            .addFile(thirdFile)
        advanceUntilIdle()
        
        // then - all files should trigger callbacks
        assertThat(onFileChangedCount).isEqualTo(3)

        // when - remove one file
        watcher.removeFile(secondFile)
        advanceUntilIdle()

        // then - other files should still be monitored
        val initialOnFileChangedCount = onFileChangedCount
        watcher.addFile(testFile) // Re-adding should not trigger (already added)
        watcher.addFile(thirdFile) // Re-adding should not trigger (already added)
        watcher.addFile(secondFile) // Adding should trigger (was removed)
        advanceUntilIdle()

        assertThat(onFileChangedCount).isEqualTo(initialOnFileChangedCount + 1)
    }

    @Test
    fun `#onFileChanged() is invoked when a watched file is modified`() = runTest {
        // given
        val callbackReceived = CompletableDeferred<Unit>()
        watcher.onFileChanged {
            callbackReceived.complete(Unit)
        }

        watcher.start()
        watcher.addFile(testFile)
        advanceUntilIdle()

        // when
        testFile.writeText("updated content")

        // then
        withTimeout(1000) {
            callbackReceived.await()
        }
    }

    private fun createFile(filename: String, content: String): Path {
        val file = tempDir.resolve(filename)
        file.createFile()
        file.writeText(content)
        return file
    }

}