package settings.ui

import MayaBundle as Loc
import settings.ApplicationSettings
import settings.PythonStubsManager
import utils.Delegate
import utils.Event

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AddEditRemovePanel
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.ToolbarDecorator
import com.intellij.util.ui.UIUtil
import logconsole.closeSdkTab
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer

private class SdkTableModel : AddEditRemovePanel.TableModel<ApplicationSettings.SdkInfo>() {
    override fun getColumnCount(): Int {
        return 3
    }

    override fun getColumnName(cIndex: Int): String {
        return when (cIndex) {
            0 -> Loc.message("mayarecharm.sdktable.MayaVersion")
            1 -> Loc.message("mayarecharm.sdktable.CommandPort")
            else -> Loc.message("mayarecharm.sdktable.Stubs")
        }
    }

    override fun getField(o: ApplicationSettings.SdkInfo, cIndex: Int): Any {
        return when (cIndex) {
            0 -> o.mayaPyPath
            1 -> o.port
            else -> formatStubsValue(o)
        }
    }

    private fun formatStubsValue(sdkInfo: ApplicationSettings.SdkInfo): String {
        val library = sdkInfo.stubsLibrary ?: return Loc.message("mayarecharm.stubs.None")
        val version = sdkInfo.stubsVersion ?: return Loc.message("mayarecharm.stubs.None")
        val outdated = sdkInfo.stubsIsOutdated
        val suffix = if (outdated) " \u2191" else ""
        return "$library $version$suffix"
    }
}

private val model = SdkTableModel()

class SdkTablePanel(private val project: Project) :
    AddEditRemovePanel<ApplicationSettings.SdkInfo>(model, arrayListOf()) {
    private val onChanged = Delegate<SdkTablePanel>()
    val pendingStubsDeletions: MutableSet<Pair<String, String>> = mutableSetOf()
    val pendingStubsRedownloads: MutableSet<Pair<String, String>> = mutableSetOf()
    val changed: Event<SdkTablePanel> get() = onChanged

    override fun addItem(): ApplicationSettings.SdkInfo? {
        val dialog = MayaSdkAddDialog(project)
        dialog.show()

        val sdk = dialog.getCreatedSdk() ?: return null
        val homePath = sdk.homePath ?: return null

        val unusedPort = ApplicationSettings.INSTANCE.getUnusedPort()
        onChanged(this)
        return ApplicationSettings.SdkInfo(homePath, unusedPort)
    }

    override fun removeItem(sdkInfo: ApplicationSettings.SdkInfo): Boolean {
        val result = Messages.showDialog(
            Loc.message("mayarecharm.sdkremove.RemoveWarning"), Loc.message("mayarecharm.sdkremove.Title"),
            arrayOf(Loc.message("mayarecharm.Yes"), Loc.message("mayarecharm.No")), 0,
            IconLoader.getIcon("/icons/MayaReCharm_Action@2x.png", this::class.java)
        ) == 0

        if (result) {
            val sdk = sdkInfo.sdk
            SdkConfigurationUtil.removeSdk(sdk)
        }
        onChanged(this)
        return result
    }

    override fun editItem(o: ApplicationSettings.SdkInfo): ApplicationSettings.SdkInfo {
        val usedPorts = data
            .asSequence()
            .filter { it.mayaPyPath != o.mayaPyPath }
            .map { it.port }
            .toSet()

        val dialog = SdkEditDialog(project, o, usedPorts, pendingStubsDeletions, pendingStubsRedownloads)
        dialog.show()
        if (!dialog.isOK) {
            return o
        }

        val edited = dialog.result
        pendingStubsDeletions.clear()
        pendingStubsDeletions.addAll(dialog.deletedStubs)
        pendingStubsRedownloads.clear()
        pendingStubsRedownloads.addAll(dialog.redownloadStubs)
        if (edited.stubsLibrary != null &&
            edited.stubsVersion != null &&
            !PythonStubsManager.isDownloaded(edited.stubsLibrary!!, edited.stubsVersion!!)
        ) {
            pendingStubsRedownloads.add(edited.stubsLibrary!! to edited.stubsVersion!!)
        }

        if (edited != o || pendingStubsDeletions.isNotEmpty() || pendingStubsRedownloads.isNotEmpty()) {
            if (edited.port != o.port) {
                closeSdkTab(project, o.mayaPyPath)
            }
            onChanged(this)
        }

        return edited
    }

    fun clearPendingStubsDeletions() {
        pendingStubsDeletions.clear()
        pendingStubsRedownloads.clear()
    }

    fun refreshStubsDisplay() {
        table.repaint()
    }

    override fun initPanel() {
        layout = BorderLayout()

        table.columnModel.getColumn(2).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val component = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column
                ) as DefaultTableCellRenderer
                val sdkInfo = data[table.convertRowIndexToModel(row)]
                val savedSdkInfo = ApplicationSettings.INSTANCE.mayaSdkMapping[sdkInfo.mayaPyPath]
                val library = savedSdkInfo?.stubsLibrary
                val version = savedSdkInfo?.stubsVersion
                component.icon = if (library != null &&
                    version != null &&
                    !PythonStubsManager.isDownloaded(library, version)
                ) {
                    AllIcons.General.Warning
                } else {
                    null
                }
                return component
            }
        }

        val decorator = ToolbarDecorator.createDecorator(table).apply {
            setAddAction { doAdd() }
            setEditAction { doEdit() }
            setRemoveAction { doRemove() }
        }

        val panel = decorator.createPanel()
        add(panel, BorderLayout.CENTER)

        labelText?.apply {
            UIUtil.addBorder(panel, IdeBorderFactory.createTitledBorder(this, false))
        }

    }
}
