package debugattach

import com.intellij.execution.process.ProcessInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.xdebugger.attach.*
import com.jetbrains.python.sdk.PythonSdkType
import settings.ApplicationSettings
import utils.pathForPid
import javax.swing.Icon

private val mayaPathsKey = Key<MutableMap<Int, String>>("mayaPathsMap")

class MayaAttachDebuggerProvider : XAttachDebuggerProvider {
    override fun getPresentationGroup(): XAttachPresentationGroup<ProcessInfo> {
        return MayaAttachGroup.INSTANCE
    }

    override fun getAvailableDebuggers(project: Project, attachHost: XAttachHost, processInfo: ProcessInfo, userData: UserDataHolder): MutableList<XAttachDebugger> {
        if (!processInfo.executableName.lowercase().contains("maya")) return mutableListOf()

        val exePath = processInfo.executableCannonicalPath.let {
            if (it.isPresent) it.get() else pathForPid(processInfo.pid) ?: return mutableListOf()
        }.lowercase()

        val currentSdk = ApplicationSettings.INSTANCE.mayaSdkMapping.values.firstOrNull {
            exePath.contains(it.mayaPath.lowercase())
        } ?: return mutableListOf()

        val mayaPathMap = userData.getUserData(mayaPathsKey) ?: mutableMapOf()
        mayaPathMap[processInfo.pid] = currentSdk.mayaPath
        userData.putUserData(mayaPathsKey, mayaPathMap)

        return mutableListOf(MayaAttachDebugger(currentSdk))
    }

    override fun isAttachHostApplicable(attachHost: XAttachHost): Boolean = attachHost is LocalAttachHost
}

private class MayaAttachDebugger(private val mayaSdk: ApplicationSettings.SdkInfo) : XAttachDebugger {
    private val mySdkHome: String? = mayaSdk.sdk.homePath
    private val myName: String = "${PythonSdkType.getInstance().getVersionString(mayaSdk.sdk)} ($mySdkHome)"

    override fun getDebuggerDisplayName(): String {
        return myName
    }

    override fun attachDebugSession(project: Project, attachHost: XAttachHost, processInfo: ProcessInfo) {
        val runner = MayaAttachToProcessDebugRunner(project, processInfo.pid, mayaSdk)
        runner.launch()
    }
}

private class MayaAttachGroup : XAttachProcessPresentationGroup {
    companion object {
        val INSTANCE = MayaAttachGroup()
    }

    override fun getItemDisplayText(project: Project, processInfo: ProcessInfo, userData: UserDataHolder): String {
        val mayaPaths = userData.getUserData(mayaPathsKey) ?: return processInfo.executableDisplayName
        return mayaPaths[processInfo.pid] ?: processInfo.executableDisplayName
    }

    override fun getItemIcon(project: Project, processInfo: ProcessInfo, userData: UserDataHolder): Icon {
        return IconLoader.getIcon("/icons/MayaReCharm_ToolWindow.png", this::class.java)
    }

    override fun getGroupName(): String {
        return "Maya"
    }

    override fun getOrder(): Int {
        return -100
    }
}
