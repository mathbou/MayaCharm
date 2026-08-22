package settings.ui

import MayaBundle as Loc
import resources.PythonStrings
import settings.ApplicationSettings
import settings.PythonStubsManager

import com.intellij.icons.AllIcons
import com.intellij.notification.Notifications
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBUI
import resources.MayaNotifications
import java.awt.Desktop
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Dimension
import java.io.IOException
import java.net.URI
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.MenuSelectionManager
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

class SdkEditDialog(
    project: Project,
    private val sdkInfo: ApplicationSettings.SdkInfo,
    private val usedPorts: Set<Int>,
    pendingDeletions: Set<Pair<String, String>>,
    private val pendingRedownloads: MutableSet<Pair<String, String>>
) :
    DialogWrapper(project, false) {
    companion object {
        private val GITHUB_URLS = mapOf(
            "maya-stubs" to "https://github.com/Muream/maya-stubs",
            "types-maya-strict" to "https://github.com/LumaPictures/cg-stubs"
        )
    }

    private val myPanel = JPanel(GridBagLayout())
    private val nameField = JTextField().apply {
        text = sdkInfo.mayaPyPath
        isEnabled = false
    }

    private val portField = JTextField().apply {
        text = sdkInfo.port.toString()
        document.addDocumentListener(object : DocumentListener {
            override fun changedUpdate(e: DocumentEvent?) {
                updateSetupText()
                updateOkButton()
            }

            override fun insertUpdate(e: DocumentEvent?) {
                updateSetupText()
                updateOkButton()
            }

            override fun removeUpdate(e: DocumentEvent?) {
                updateSetupText()
                updateOkButton()
            }
        })
    }

    private val setupText = EditorTextField(
        PythonStrings.CMDPORTSETUPSCRIPT.getResource(0),
        project, FileTypeManager.getInstance().getFileTypeByExtension(".py")
    ).apply {
        setOneLineMode(false)
        isEnabled = false
    }
    private val downloadedIcon: Icon = AllIcons.Actions.MenuSaveall
    private val missingIcon: Icon = AllIcons.General.Warning
    private var selectedStubsLibrary: String? = sdkInfo.stubsLibrary
    private var selectedStubsVersion: String? = sdkInfo.stubsVersion
    private val requestedDeletions = pendingDeletions.toMutableSet()
    private val stubsButton = JButton().apply {
        addActionListener { showStubsMenu() }
    }
    private val stubsGitHubButton = JButton(AllIcons.Vcs.Vendors.Github).apply {
        toolTipText = Loc.message("mayarecharm.stubs.GitHub")
        preferredSize = Dimension(40, preferredSize.height)
        minimumSize = Dimension(25, 20)
        isEnabled = false
        addActionListener { openSelectedGithub() }
    }

    init {
        title = Loc.message("mayarecharm.sdkedit.windowName")
        updateSetupText()
        updateStubsButtonText()
        updateOkButton()
        updateStubsGitHubButton()
        init()

        with(GridBagConstraints()) {
            insets = JBUI.insets(2)
            gridx = 0
            gridy = 0
            fill = GridBagConstraints.HORIZONTAL
            gridwidth = 1

            weightx = 0.0
            myPanel.add(JLabel(Loc.message("mayarecharm.sdkedit.SdkName"), JLabel.RIGHT), this)

            gridx = 1
            weightx = 1.0
            myPanel.add(nameField, this)

            gridy++
            gridx = 0
            weightx = 0.0
            myPanel.add(JLabel(Loc.message("mayarecharm.sdkedit.PortNumber"), JLabel.RIGHT), this)

            gridx = 1
            weightx = 1.0
            myPanel.add(portField, this)

            gridy++
            gridx = 0
            weightx = 0.0
            gridwidth = 1
            myPanel.add(JLabel(Loc.message("mayarecharm.sdkedit.Stubs"), JLabel.RIGHT), this)

            gridx = 1
            weightx = 1.0
            myPanel.add(stubsButton, this)

            gridx = 2
            weightx = 0.0
            insets = JBUI.insets(2, 0, 2, 2)
            myPanel.add(stubsGitHubButton, this)

            gridy++
            gridx = 0
            weightx = 1.0
            gridwidth = 4
            insets = JBUI.insets(2)
            myPanel.add(
                JLabel(
                    Loc.message("mayarecharm.sdkedit.ExplainUserSetup"),
                    JLabel.LEFT
                ), this
            )

            gridy++
            myPanel.add(setupText, this)
        }
    }

    override fun createCenterPanel(): JComponent {
        return myPanel
    }

    val result: ApplicationSettings.SdkInfo
        get() = if (isOK) {
            sdkInfo.copy(
                port = portField.text.toInt(),
                stubsLibrary = selectedStubsLibrary,
                stubsVersion = selectedStubsVersion
            )
        } else {
            sdkInfo
        }

    val deletedStubs: Set<Pair<String, String>>
        get() = requestedDeletions

    val redownloadStubs: Set<Pair<String, String>>
        get() = pendingRedownloads

    private fun updateSetupText() {
        setupText.text = PythonStrings.CMDPORTSETUPSCRIPT.getResource(portField.text.toIntOrNull() ?: sdkInfo.port)
    }

    private fun isModified(): Boolean {
        val port = portField.text.toIntOrNull() ?: return false
        val selectedStubsMissing = selectedStubsLibrary != null &&
            selectedStubsVersion != null &&
            !PythonStubsManager.isDownloaded(selectedStubsLibrary!!, selectedStubsVersion!!)
        return sdkInfo.port != port ||
            sdkInfo.stubsLibrary != selectedStubsLibrary ||
            sdkInfo.stubsVersion != selectedStubsVersion ||
            requestedDeletions.isNotEmpty() ||
            selectedStubsMissing
    }

    private fun isPortAvailable(): Boolean {
        val port = portField.text.toIntOrNull() ?: return false
        return port !in usedPorts
    }

    private fun updateOkButton() {
        isOKActionEnabled = isModified() && isPortAvailable()
    }

    private fun showStubsMenu() {
        val versions = loadVersions() ?: return
        val popup = JPopupMenu()
        popup.add(
            JMenuItem(Loc.message("mayarecharm.stubs.None")).apply {
                isEnabled = selectedStubsLibrary != null
                addActionListener {
                    selectedStubsLibrary = null
                    selectedStubsVersion = null
                    updateStubsButtonText()
                    updateOkButton()
                }
            }
        )
        popup.addSeparator()

        for (library in PythonStubsManager.supportedLibraries) {
            val libraryMenu = JMenu(library)
            val available = versions[library].orEmpty()
            if (available.isEmpty()) {
                libraryMenu.add(
                    JMenuItem(Loc.message("mayarecharm.stubs.NoVersions")).apply { isEnabled = false }
                )
            } else {
                for (version in available) {
                    val selected = selectedStubsLibrary == library && selectedStubsVersion == version
                    val versionLabel = if (selected) "\u2713 $version" else version
                    val isDownloaded = PythonStubsManager.isDownloaded(library, version)
                    val isSelectedAndMissing = selected &&
                        sdkInfo.stubsLibrary == library &&
                        sdkInfo.stubsVersion == version &&
                        !isDownloaded
                    libraryMenu.add(
                        VersionMenuItem(versionLabel, isDownloaded).apply {
                            if (isDownloaded) {
                                icon = downloadedIcon
                                toolTipText = Loc.message("mayarecharm.stubs.Downloaded", version)
                            } else if (isSelectedAndMissing) {
                                icon = missingIcon
                                toolTipText = Loc.message("mayarecharm.stubs.Missing", version)
                            }
                            addMouseListener(object : MouseAdapter() {
                                private var popupGesture = false
                                private var popupDeleted = false

                                private fun selectVersion() {
                                    requestedDeletions.remove(library to version)
                                    pendingRedownloads.remove(library to version)
                                    selectedStubsLibrary = library
                                    selectedStubsVersion = version
                                    updateStubsButtonText()
                                    updateOkButton()
                                }

                                private fun deleteVersion() {
                                    requestDownloadedVersionDeletion(library, version)
                                    MenuSelectionManager.defaultManager().clearSelectedPath()
                                }

                                private fun handlePopupTrigger(e: MouseEvent) {
                                    if (e.isPopupTrigger && isDownloaded && !popupDeleted) {
                                        deleteVersion()
                                        popupDeleted = true
                                    }
                                }

                                override fun mousePressed(e: MouseEvent) {
                                    popupGesture = SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger
                                    handlePopupTrigger(e)
                                }

                                override fun mouseReleased(e: MouseEvent) {
                                    if (popupGesture) {
                                        handlePopupTrigger(e)
                                        popupGesture = false
                                        popupDeleted = false
                                    } else if (SwingUtilities.isLeftMouseButton(e)) {
                                        selectVersion()
                                        MenuSelectionManager.defaultManager().clearSelectedPath()
                                    }
                                }
                            })
                        }
                    )
                }
            }
            popup.add(libraryMenu)
        }

        popup.show(stubsButton, 0, stubsButton.height)
    }

    private fun loadVersions(): Map<String, List<String>>? {
        return try {
            PythonStubsManager.supportedLibraries.associateWith { library ->
                PythonStubsManager.getVersions(library)
            }
        } catch (e: IOException) {
            Notifications.Bus.notify(MayaNotifications.stubsOperationFailed(e.message ?: e.javaClass.simpleName))
            null
        } catch (e: IllegalStateException) {
            Notifications.Bus.notify(MayaNotifications.stubsOperationFailed(e.message ?: e.javaClass.simpleName))
            null
        } catch (e: Exception) {
            Notifications.Bus.notify(MayaNotifications.stubsOperationFailed(e.message ?: e.javaClass.simpleName))
            null
        }
    }

    private fun requestDownloadedVersionDeletion(library: String, version: String) {
        val selected = selectedStubsLibrary == library && selectedStubsVersion == version
        val usedByOtherSdk = ApplicationSettings.INSTANCE.mayaSdkMapping.values.any {
            it.mayaPyPath != sdkInfo.mayaPyPath &&
                it.stubsLibrary == library &&
                it.stubsVersion == version
        }
        if (selected) {
            Notifications.Bus.notify(
                MayaNotifications.stubsOperationFailed(
                    Loc.message("mayarecharm.stubs.DeleteInUse", library, version)
                )
            )
            return
        }
        if (usedByOtherSdk) {
            Notifications.Bus.notify(
                MayaNotifications.stubsOperationFailed(
                    Loc.message("mayarecharm.stubs.DeleteInUse", library, version)
                )
            )
            return
        }

        requestedDeletions.add(library to version)
        updateOkButton()
    }

    private fun updateStubsButtonText() {
        val library = selectedStubsLibrary
        val version = selectedStubsVersion
        if (library == null || version == null) {
            stubsButton.text = Loc.message("mayarecharm.stubs.None")
            updateStubsGitHubButton()
            return
        }

        val latest = PythonStubsManager.latestKnownVersion(library)
        val suffix = if (latest != null && latest != version) " \u2191" else ""
        stubsButton.text = "$library $version$suffix"
        updateStubsGitHubButton()
    }

    private fun updateStubsGitHubButton() {
        val library = selectedStubsLibrary
        stubsGitHubButton.isVisible = library != null
        stubsGitHubButton.isEnabled = library != null && GITHUB_URLS.containsKey(library)
    }

    private fun openSelectedGithub() {
        val library = selectedStubsLibrary ?: return
        val url = GITHUB_URLS[library] ?: return
        try {
            Desktop.getDesktop().browse(URI(url))
        } catch (e: Exception) {
            Notifications.Bus.notify(MayaNotifications.stubsOperationFailed(e.message ?: e.javaClass.simpleName))
        }
    }

    private class VersionMenuItem(
        label: String,
        isDownloaded: Boolean
    ) : JMenuItem(label) {
        init {
            if (!isDownloaded) {
                icon = null
            }
        }
    }
}
