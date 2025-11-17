import com.redhat.devtools.gateway.openshift.DevWorkspace

interface DevWorkspaceListener {
    fun onAdded(dw: DevWorkspace)
    fun onUpdated(dw: DevWorkspace)
    fun onDeleted(name: String)
}
