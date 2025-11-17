import com.redhat.devtools.gateway.openshift.DevWorkspace
import groovyjarjarantlr.NameSpace

interface DevWorkspaceListener {
    fun onAdded(dw: DevWorkspace)
    fun onUpdated(dw: DevWorkspace)
    fun onDeleted(dw: DevWorkspace)
}
