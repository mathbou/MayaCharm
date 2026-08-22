package settings

import flavors.MayaSdkFlavor
import mayacomms.mayaFromMayaPy
import resources.MayaNotifications

import com.intellij.openapi.components.*
import com.intellij.notification.Notifications
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import java.util.*

typealias SdkPortMap = MutableMap<String, ApplicationSettings.SdkInfo>

private val portRange = (4434..4534).toSet()

@State(
    name = "MCAppSettings",
    storages = [Storage(value = "mayarecharm.settings.xml", roamingType = RoamingType.DISABLED)]
)
class ApplicationSettings : PersistentStateComponent<ApplicationSettings.State> {
    data class SdkInfo(
        var mayaPyPath: String = "",
        var port: Int = -1,
        var stubsLibrary: String? = null,
        var stubsVersion: String? = null
    ) {
        val mayaPath: String
            get() = mayaFromMayaPy(mayaPyPath) ?: ""

        val sdk: Sdk
            get() = INSTANCE.findByPath(mayaPyPath)!!

        val stubsIsOutdated: Boolean
            get() {
                val library = stubsLibrary ?: return false
                val version = stubsVersion ?: return false
                val latest = PythonStubsManager.latestKnownVersion(library) ?: return false
                return latest != version
            }
    }

    data class State(var mayaSdkMapping: SdkPortMap = mutableMapOf())

    private var myState = State()
    private var missingStubsNotificationShown = false

    companion object {
        val INSTANCE: ApplicationSettings
            get() = service()
    }

    init {
        reloadMayaSdkMapping()
    }

    fun findByPath(path: String): Sdk? {
        return ProjectJdkTable.getInstance().allJdks.firstOrNull { sdk -> sdk.homePath == path }
    }

    var mayaSdkMapping: SdkPortMap
        get() = myState.mayaSdkMapping
        set(value) {
            myState.mayaSdkMapping = value
        }

    override fun getState(): State {
        return myState
    }

    override fun loadState(state: State) {
        reloadMayaSdkMapping(state.mayaSdkMapping)
    }

    fun checkMissingStubs(project: Project) {
        val hasMissingStubs = mayaSdkMapping.values.any { sdkInfo ->
            val library = sdkInfo.stubsLibrary
            val version = sdkInfo.stubsVersion
            library != null &&
                version != null &&
                !PythonStubsManager.isDownloaded(library, version)
        }
        if (hasMissingStubs && !missingStubsNotificationShown) {
            Notifications.Bus.notify(MayaNotifications.stubsMissing(project))
            missingStubsNotificationShown = true
        }
    }

    fun refreshPythonSdks() {
        reloadMayaSdkMapping(mayaSdkMapping)
    }

    private fun reloadMayaSdkMapping(savedMapping: Map<String, SdkInfo> = emptyMap()) {
        val reloadedMapping = mutableMapOf<String, SdkInfo>()

        for (path in getRegisteredMayaSdkPaths()) {
            reloadedMapping[path] = savedMapping[path]?.copy(mayaPyPath = path) ?: SdkInfo(path, -1)
        }

        mayaSdkMapping.clear()
        mayaSdkMapping.putAll(reloadedMapping)
        assignEmptyPorts()
    }

    private fun getRegisteredMayaSdkPaths(): List<String> {
        return ProjectJdkTable.getInstance().allJdks
            .mapNotNull { sdk -> sdk.homePath }
            .filter(MayaSdkFlavor::isValidMayaSdkPath)
            .distinct()
    }

    private fun assignEmptyPorts() {
        val usedPorts = mayaSdkMapping.map { it.value.port }.filter { it > 0 }.toSet()
        val freePorts = PriorityQueue((portRange - usedPorts).sorted())

        for (key in mayaSdkMapping.filter { it.value.port < 0 }.keys) {
            mayaSdkMapping[key]!!.port = freePorts.remove()
        }
    }

    fun getUnusedPort(): Int {
        val usedPorts = mayaSdkMapping.map { it.value.port }.filter { it > 0 }.toSet()
        val freePorts = PriorityQueue((portRange - usedPorts).sorted())
        return freePorts.remove()
    }
}
