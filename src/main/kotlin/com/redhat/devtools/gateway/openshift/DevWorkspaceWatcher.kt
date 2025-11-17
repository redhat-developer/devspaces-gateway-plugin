import com.intellij.openapi.application.EDT
import com.redhat.devtools.gateway.openshift.DevWorkspace
import io.kubernetes.client.util.Watch
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DevWorkspaceWatcher(
    private val namespace: String,
    private val createWatcher: (namespace: String) -> Watch<Any>,
    private val listener: DevWorkspaceListener,
    private val debounceMillis: Long = 150,
    private val scope: CoroutineScope
) {

    private var job: Job? = null
    private val pending = mutableMapOf<String, DevWorkspace>()
    private val deleted = mutableSetOf<String>()
    private val mutex = Mutex()

    fun start() {
        job = scope.launch {
            watchLoop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun watchLoop() {
        println("WATCHER STARTED on thread ${Thread.currentThread().name}")

        while (scope.isActive) {
            val watcher = createWatcher(namespace)

            try {
                var lastFlush = System.currentTimeMillis()

                for (event in watcher) {
                    println("EVENT RECEIVED ${event.type} on ${Thread.currentThread().name}: ${event.type}")


                    if (!scope.isActive) break

                    val type = event.type
                    val dw = DevWorkspace.from(event.`object`)
                    val name = dw.name

                    mutex.withLock {
                        when (type) {
                            "ADDED" -> {
                                pending[name] = dw
                                deleted.remove(name)
                            }
                            "MODIFIED" -> {
                                pending[name] = dw
                            }
                            "DELETED" -> {
                                pending.remove(name)
                                deleted.add(name)
                            }
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastFlush > debounceMillis) {
                        flush()
                        lastFlush = now
                    }
                }
            } catch (e: Exception) {
                // connection dropped or closed — reconnect
                println("watchLoop exception: $e" )
            } finally {
                watcher.close()
            }

            flush() // flush before restarting watcher
            delay(200)
        }
    }

    private suspend fun flush() {
        val updates: Map<String, DevWorkspace>
        val removals: Set<String>

        mutex.withLock {
            updates = pending.toMap()
            removals = deleted.toSet()
            pending.clear()
            deleted.clear()
        }

        if (updates.isEmpty() && removals.isEmpty()) return

        try {
            withContext(Dispatchers.EDT) {
                for ((_, dw) in updates) listener.onUpdated(dw)
                for (name in removals) listener.onDeleted(name)
            }
        } catch (_: CancellationException) {
            // Ignore: parent is cancelling, UI is shutting down.
            // Do NOT rethrow cancellation — let coroutine complete normally.
        }
    }
}
