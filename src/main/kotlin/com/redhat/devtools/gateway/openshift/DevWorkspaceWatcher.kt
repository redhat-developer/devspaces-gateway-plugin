import com.intellij.openapi.application.EDT
import com.redhat.devtools.gateway.openshift.DevWorkspace
import io.kubernetes.client.util.Watch
import kotlinx.coroutines.*

class DevWorkspaceWatcher(
    private val namespace: String,
    private val createWatcher: (namespace: String) -> Watch<Any>,
    private val createFilter: (String) -> ((DevWorkspace) -> Boolean),
    private val listener: DevWorkspaceListener,
    private val scope: CoroutineScope
) {
    private var job: Job? = null

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
        while (scope.isActive) {
            val watcher = createWatcher(namespace)
            val matches = createFilter(namespace)

            try {
                for (event in watcher) {
                    if (!scope.isActive) break

                    val dw = DevWorkspace.from(event.`object`)
                    withContext(Dispatchers.EDT) {
                        when (event.type) {
                            "ADDED"    -> if(matches(dw)) listener.onAdded(dw)
                            "MODIFIED" -> if(matches(dw)) listener.onUpdated(dw) else listener.onDeleted(dw)
                            "DELETED"  -> listener.onDeleted(dw)
                        }
                    }
                }
            } catch (_: Exception) {
                // connection dropped or closed — reconnect
            } finally {
                watcher.close()
            }

            delay(100)
        }
    }
}
