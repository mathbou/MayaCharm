package logconsole

import MayaBundle as Loc
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import mayacomms.LOG_FILENAME_STRING
import settings.ApplicationSettings
import settings.ProjectSettings
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.ui.popup.JBPopupFactory
import flavors.INSTANCE
import java.io.File
import java.nio.charset.Charset
import java.awt.event.MouseEvent

private const val MAYA_LOG_TOOL_WINDOW_ID = "MayaLog"
private val sdkPathKey = Key.create<String>("MayaReCharm.LogWindow.sdkPath")

fun closeSdkTab(project: Project, sdkPath: String): Boolean {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(MAYA_LOG_TOOL_WINDOW_ID) ?: return false
    val contentManager = toolWindow.contentManager
    val contentToClose = contentManager.contents.firstOrNull { it.getUserData(sdkPathKey) == sdkPath } ?: return false
    contentManager.removeContent(contentToClose, true)
    return true
}

class LogWindow : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val projectSettings = ProjectSettings.getInstance(project)
        val contentManager = toolWindow.contentManager
        val sdkEntries = getSortedSdkEntries()
        val sdkByPath = sdkEntries.associateBy { it.key }
        val initialOpenSdkPaths = getInitialOpenSdkPaths(projectSettings, sdkByPath)
        val initiallySelectedSdkPath = when {
            projectSettings.selectedLogSdkPath in initialOpenSdkPaths -> projectSettings.selectedLogSdkPath
            projectSettings.selectedSdkName in initialOpenSdkPaths -> projectSettings.selectedSdkName
            else -> initialOpenSdkPaths.firstOrNull()
        }

        var selectedContent: Content? = null
        var selectedConsole: LogConsole? = null

        for (sdkPath in initialOpenSdkPaths) {
            val sdkInfo = sdkByPath[sdkPath]?.value ?: continue
            val (content, console) = addSdkTab(project, contentManager, sdkPath, sdkInfo, select = false)

            if (initiallySelectedSdkPath != null && initiallySelectedSdkPath == sdkPath) {
                selectedContent = content
                selectedConsole = console
            }
        }

        selectedContent?.let(contentManager::setSelectedContent)
        registerTabStatePersistence(projectSettings, contentManager)
        persistTabState(projectSettings, contentManager)

        val reopenAction = ReopenLogTabAction(project, toolWindow)
        (toolWindow as? ToolWindowEx)?.setTabActions(reopenAction)

        toolWindow.setAvailable(true, null)
        val consoleToActivate = selectedConsole
        if (consoleToActivate != null) {
            toolWindow.activate(consoleToActivate::activate)
        }
    }

    private fun getSortedSdkEntries(): List<Map.Entry<String, ApplicationSettings.SdkInfo>> {
        return ApplicationSettings.INSTANCE.mayaSdkMapping.entries
            .sortedWith(compareBy<Map.Entry<String, ApplicationSettings.SdkInfo>> { it.value.port }.thenBy { it.key })
    }

    private fun getInitialOpenSdkPaths(
        projectSettings: ProjectSettings,
        sdkByPath: Map<String, Map.Entry<String, ApplicationSettings.SdkInfo>>
    ): List<String> {
        val restoredOpenTabs = projectSettings.openLogSdkPaths?.filter { it in sdkByPath }
        if (restoredOpenTabs != null) {
            return restoredOpenTabs
        }

        return listOfNotNull(projectSettings.selectedSdkName)
            .filter { it in sdkByPath }
    }

    private fun addSdkTab(
        project: Project,
        contentManager: ContentManager,
        sdkPath: String,
        sdkInfo: ApplicationSettings.SdkInfo,
        select: Boolean
    ): Pair<Content, LogConsole> {
        val port = sdkInfo.port
        val year = INSTANCE.getYear(sdkPath) ?: "-"
        val console = createConsole(project, port)
        val content = ContentFactory.getInstance().createContent(console.component, "Maya $year ($port)", false)
        content.putUserData(sdkPathKey, sdkPath)
        contentManager.addContent(content)

        if (select) {
            contentManager.setSelectedContent(content)
            console.activate()
        }

        return content to console
    }

    private fun isSdkTabOpen(contentManager: ContentManager, sdkPath: String): Boolean {
        return contentManager.contents.any { it.getUserData(sdkPathKey) == sdkPath }
    }

    private fun registerTabStatePersistence(projectSettings: ProjectSettings, contentManager: ContentManager) {
        contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentAdded(event: ContentManagerEvent) {
                persistTabState(projectSettings, contentManager)
            }

            override fun contentRemoved(event: ContentManagerEvent) {
                persistTabState(projectSettings, contentManager)
            }

            override fun selectionChanged(event: ContentManagerEvent) {
                persistTabState(projectSettings, contentManager)
            }
        })
    }

    private fun persistTabState(projectSettings: ProjectSettings, contentManager: ContentManager) {
        val openSdkPaths = contentManager.contents
            .mapNotNull { it.getUserData(sdkPathKey) }
            .filter { it in ApplicationSettings.INSTANCE.mayaSdkMapping }

        projectSettings.openLogSdkPaths = openSdkPaths
        projectSettings.selectedLogSdkPath = contentManager.selectedContent
            ?.getUserData(sdkPathKey)
            ?.takeIf { it in openSdkPaths }
    }

    private fun createConsole(project: Project, port: Int): LogConsole {
        val mayaLogPath = File(System.getProperty("java.io.tmpdir"), String.format(LOG_FILENAME_STRING, port))
        return LogConsole(
            project,
            mayaLogPath,
            port,
            Charset.defaultCharset(),
            0L,
            "MayaLog",
            true,
            GlobalSearchScope.allScope(project)
        )
    }

    private inner class ReopenLogTabAction(
        private val project: Project,
        private val toolWindow: ToolWindow
    ) : DumbAwareAction(
        Loc.message("mayarecharm.logwindow.ReopenTabActionText"),
        Loc.message("mayarecharm.logwindow.ReopenTabActionDescription"),
        AllIcons.General.ArrowDown
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            val hasMissingTabs = getSortedSdkEntries()
                .any { (sdkPath, _) -> !isSdkTabOpen(toolWindow.contentManager, sdkPath) }
            e.presentation.isVisible = hasMissingTabs
            e.presentation.isEnabled = hasMissingTabs
        }

        override fun actionPerformed(e: AnActionEvent) {
            val contentManager = toolWindow.contentManager
            val missingEntries = getSortedSdkEntries()
                .filter { (sdkPath, _) -> !isSdkTabOpen(contentManager, sdkPath) }

            if (missingEntries.isEmpty()) {
                return
            }

            val menu = DefaultActionGroup()
            for ((sdkPath, sdkInfo) in missingEntries) {
                val port = sdkInfo.port
                val year = INSTANCE.getYear(sdkPath) ?: "-"
                val label = "Maya $year ($port)"

                menu.add(object : DumbAwareAction(label) {
                    override fun actionPerformed(e: AnActionEvent) {
                        addSdkTab(project, contentManager, sdkPath, sdkInfo, select = true)
                    }
                })
            }

            val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                Loc.message("mayarecharm.logwindow.ReopenTabDialogTitle"),
                menu,
                e.dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true
            )

            val mousePoint = (e.inputEvent as? MouseEvent)?.let { RelativePoint(it.component, it.point) }
            if (mousePoint != null) {
                popup.show(mousePoint)
            } else {
                popup.show(RelativePoint.getCenterOf(toolWindow.component))
            }
        }
    }
}
