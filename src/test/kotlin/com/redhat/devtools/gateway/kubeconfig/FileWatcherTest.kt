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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import kotlin.io.path.deleteExisting
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
        var onFileChangedPath: Path? = null
        watcher.onFileChanged { path -> onFileChangedPath = path }

        watcher.addFile(testFile)
        advanceUntilIdle()

        assertThat(onFileChangedPath).isEqualTo(testFile)
        assertThat(watcher.getMonitoredFiles()).containsExactly(testFile)
    }

    @Test
    fun `#addFile() invokes callback for non-existent files that are tracked`() = runTest {
        var onFileChangedCount = 0
        watcher.onFileChanged { onFileChangedCount++ }
        val nonExistentFile = tempDir.resolve("non-existent.yaml")

        watcher.addFile(nonExistentFile)
        advanceUntilIdle()

        assertThat(onFileChangedCount).isEqualTo(1)
        assertThat(watcher.getMonitoredFiles()).containsExactly(nonExistentFile)
    }

    @Test
    fun `#addFile() invokes callback for each file when multiple are added`() = runTest {
        var onFileChangedCount = 0
        watcher.onFileChanged { onFileChangedCount++ }
        val secondFile = createFile("second-file.yaml", "second content")

        watcher
            .addFile(testFile)
            .addFile(secondFile)
        advanceUntilIdle()

        assertThat(onFileChangedCount).isEqualTo(2)
        assertThat(watcher.getMonitoredFiles()).containsExactlyInAnyOrder(testFile, secondFile)
    }

    @Test
    fun `#addFile() notifies again when re-adding after remove`() = runTest {
        var onFileChangedCount = 0
        watcher.onFileChanged { onFileChangedCount++ }

        watcher.addFile(testFile)
        advanceUntilIdle()
        assertThat(onFileChangedCount).isEqualTo(1)

        watcher.removeFile(testFile)
        advanceUntilIdle()
        assertThat(watcher.getMonitoredFiles()).isEmpty()

        watcher.addFile(testFile)
        advanceUntilIdle()
        assertThat(onFileChangedCount).isEqualTo(2)
        assertThat(watcher.getMonitoredFiles()).containsExactly(testFile)
    }

    @Test
    fun `#addFile() does not invoke callback when file is already monitored`() = runTest {
        var onFileChangedPath: Path? = null
        watcher.onFileChanged { path -> onFileChangedPath = path }

        watcher.addFile(testFile)
        advanceUntilIdle()

        onFileChangedPath = null
        watcher.addFile(testFile)
        advanceUntilIdle()

        assertThat(onFileChangedPath).isNull()
        assertThat(watcher.getMonitoredFiles()).containsExactly(testFile)
    }

    @Test
    fun `#removeFile() handles non-existent files gracefully`() = runTest {
        val nonExistentFile = tempDir.resolve("never-added.yaml")

        val result = watcher.removeFile(nonExistentFile)

        assertThat(result).isSameAs(watcher)
    }

    @Test
    fun `#removeFile() unregisters directory when last file is removed`() = runTest {
        watcher.addFile(testFile)
        advanceUntilIdle()
        assertThat(watcher.getWatchedDirectories()).containsExactly(testFile.parent)

        watcher.removeFile(testFile)
        advanceUntilIdle()

        assertThat(watcher.getMonitoredFiles()).isEmpty()
        assertThat(watcher.getWatchedDirectories()).isEmpty()
    }

    @Test
    fun `#removeFile() keeps directory watch while sibling files remain`() = runTest {
        val secondFile = createFile("second-file.yaml", "second content")

        watcher.addFile(testFile).addFile(secondFile)
        advanceUntilIdle()
        assertThat(watcher.getWatchedDirectories()).containsExactly(testFile.parent)

        watcher.removeFile(testFile)
        advanceUntilIdle()

        assertThat(watcher.getMonitoredFiles()).containsExactly(secondFile)
        assertThat(watcher.getWatchedDirectories()).containsExactly(testFile.parent)
    }

    @Test
    fun `#addFile() registers a directory watch only once for siblings`() = runTest {
        val secondFile = createFile("second-file.yaml", "second content")

        watcher.addFile(testFile).addFile(secondFile)
        advanceUntilIdle()

        assertThat(watcher.getWatchedDirectories()).containsExactly(testFile.parent)
    }

    @Test
    fun `#removeFile() keeps other files monitored`() = runTest {
        val secondFile = createFile("second-file.yaml", "second content")
        val thirdFile = createFile("third-file.yaml", "third content")

        watcher
            .addFile(testFile)
            .addFile(secondFile)
            .addFile(thirdFile)
        advanceUntilIdle()

        assertThat(watcher.getMonitoredFiles()).containsExactlyInAnyOrder(testFile, secondFile, thirdFile)

        watcher.removeFile(secondFile)
        advanceUntilIdle()

        assertThat(watcher.getMonitoredFiles()).containsExactlyInAnyOrder(testFile, thirdFile)

        watcher.addFile(secondFile)
        advanceUntilIdle()
        assertThat(watcher.getMonitoredFiles()).containsExactlyInAnyOrder(testFile, secondFile, thirdFile)
    }

    @Test
    fun `#getMonitoredFiles() includes non-existent files`() = runTest {
        val existingFile = createFile("existing.yaml", "content")
        val nonExistentFile = tempDir.resolve("never-exists.yaml")

        watcher.addFile(existingFile)
        watcher.addFile(nonExistentFile)
        advanceUntilIdle()

        assertThat(watcher.getMonitoredFiles()).containsExactlyInAnyOrder(existingFile, nonExistentFile)
    }

    @Test
    fun `#onFileChanged() is invoked when a watched file is modified`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ioWatcher = FileWatcher(ioScope, Dispatchers.IO)
        try {
            val callbackReceived = CompletableDeferred<Unit>()
            var notifyCount = 0
            ioWatcher.onFileChanged {
                notifyCount++
                // First notify is from addFile; complete on a subsequent FS event.
                if (notifyCount > 1) {
                    callbackReceived.complete(Unit)
                }
            }
            ioWatcher.start()
            ioWatcher.addFile(testFile)
            delay(200)

            testFile.writeText("updated content")

            @Suppress("ConvertLongToDuration")
            withTimeout(5_000) {
                callbackReceived.await()
            }
        } finally {
            ioWatcher.stop()
            ioScope.cancel()
        }
    }

    @Test
    fun `#onFileChanged() is invoked when a watched file is deleted`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ioWatcher = FileWatcher(ioScope, Dispatchers.IO)
        try {
            val callbackReceived = CompletableDeferred<Path>()
            var notifyCount = 0
            ioWatcher.onFileChanged { path ->
                notifyCount++
                if (notifyCount > 1) {
                    callbackReceived.complete(path)
                }
            }
            ioWatcher.start()
            ioWatcher.addFile(testFile)
            delay(200)

            testFile.deleteExisting()

            @Suppress("ConvertLongToDuration")
            withTimeout(5_000) {
                assertThat(callbackReceived.await()).isEqualTo(testFile)
            }
        } finally {
            ioWatcher.stop()
            ioScope.cancel()
        }
    }

    private fun createFile(filename: String, content: String): Path {
        val file = tempDir.resolve(filename)
        file.createFile()
        file.writeText(content)
        return file
    }
}
