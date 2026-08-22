package settings

import MayaBundle as Loc
import resources.MayaNotifications
import settings.ui.SdkTablePanel

import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.util.ui.JBUI
import java.io.IOException
import java.awt.*
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener

class MayaSdkConfigurable(private val project: Project) : SearchableConfigurable, Configurable.NoScroll {
    companion object {
        const val ID = "settings.MayaSdkConfigurable"
    }

    private val settings = ApplicationSettings.INSTANCE

    private val myPanel = JPanel(GridBagLayout()).also {
        it.addAncestorListener(object : AncestorListener {
            override fun ancestorAdded(event: AncestorEvent?) {
                ApplicationSettings.INSTANCE.refreshPythonSdks()
                reset()
            }

            override fun ancestorMoved(event: AncestorEvent?) {}

            override fun ancestorRemoved(event: AncestorEvent?) {}
        })
    }

    private val mySdkPanel = SdkTablePanel(project).also {
        it.changed += {
            ApplicationSettings.INSTANCE.refreshPythonSdks()
            ApplicationManager.getApplication().invokeLater({
                updateDownloadMissingStubsButton()
            }, ModalityState.any())
        }
    }
    private val downloadMissingStubsButton = JButton(
        Loc.message("mayarecharm.stubs.DownloadMissing")
    ).apply {
        addActionListener { downloadMissingStubs() }
    }

    init {
        with(GridBagConstraints()) {
            insets = JBUI.insets(2)
            weightx = 1.0
            gridx = 0
            gridy = 0

            fill = GridBagConstraints.HORIZONTAL

            insets = JBUI.insets(2, 2, 0, 2)
            gridy = 1
            weighty = 1.0
            gridheight = GridBagConstraints.RELATIVE
            fill = GridBagConstraints.BOTH
            myPanel.add(mySdkPanel, this)

            gridy = 2
            weighty = 0.0
            fill = GridBagConstraints.NONE
            anchor = GridBagConstraints.WEST
            myPanel.add(downloadMissingStubsButton, this)
        }
    }

    override fun getId(): String {
        return ID
    }

    override fun getDisplayName(): String {
        return "MayaReCharm"
    }

    override fun getHelpTopic(): String? {
        return null // TODO
    }

    override fun createComponent(): JComponent {
        return myPanel
    }

    override fun isModified(): Boolean {
        val entries = settings.mayaSdkMapping.values.toSet() != mySdkPanel.data.toSet()
        return entries ||
            mySdkPanel.pendingStubsDeletions.isNotEmpty() ||
            mySdkPanel.pendingStubsRedownloads.isNotEmpty()
    }

    override fun reset() {
        mySdkPanel.data.clear()
        mySdkPanel.data.addAll(settings.mayaSdkMapping.values.sortedBy { it.mayaPyPath }.map { it.copy() })
        mySdkPanel.clearPendingStubsDeletions()
        updateDownloadMissingStubsButton()
    }

    override fun apply() {
        val previous = settings.mayaSdkMapping
        val next = mySdkPanel.data.associateBy { it.mayaPyPath }.toMutableMap()
        val pendingDeletions = mySdkPanel.pendingStubsDeletions.toSet()
        val pendingRedownloads = mySdkPanel.pendingStubsRedownloads.toSet()

        settings.mayaSdkMapping = next
        mySdkPanel.clearPendingStubsDeletions()

        object : Task.Backgroundable(project, Loc.message("mayarecharm.stubs.OperationTitle")) {
            override fun run(indicator: ProgressIndicator) {
                val downloadedStubs = mutableSetOf<String>()
                for ((library, version) in pendingDeletions) {
                    if (next.values.any { it.stubsLibrary == library && it.stubsVersion == version }) {
                        continue
                    }
                    try {
                        PythonStubsManager.deleteDownloaded(library, version)
                    } catch (e: IOException) {
                        notifyStubsFailure(e)
                    } catch (e: Exception) {
                        notifyStubsFailure(e)
                    }
                }

                for ((path, nextInfo) in next) {
                    val previousInfo = previous[path]
                    val stubsChanged = previousInfo?.stubsLibrary != nextInfo.stubsLibrary ||
                        previousInfo?.stubsVersion != nextInfo.stubsVersion
                    val selectedLibrary = nextInfo.stubsLibrary
                    val selectedVersion = nextInfo.stubsVersion
                    val selectedStubsMissing = selectedLibrary != null &&
                        selectedVersion != null &&
                        !PythonStubsManager.isDownloaded(selectedLibrary, selectedVersion)
                    val redownloadRequested = selectedLibrary != null &&
                        selectedVersion != null &&
                        selectedLibrary to selectedVersion in pendingRedownloads
                    if (!stubsChanged && !selectedStubsMissing && !redownloadRequested) continue

                    val sdk = settings.findByPath(path) ?: continue
                    try {
                        val library = selectedLibrary
                        val version = selectedVersion
                        if (library != null && version != null) {
                            indicator.text = "$library $version"
                            val wasDownloaded = PythonStubsManager.isDownloaded(library, version)
                            PythonStubsManager.ensureDownloaded(library, version)
                            if (!wasDownloaded) downloadedStubs.add("$library $version")
                        }
                        applyStubsToSdk(sdk, library, version)
                    } catch (e: IOException) {
                        notifyStubsFailure(e)
                    } catch (e: IllegalStateException) {
                        notifyStubsFailure(e)
                    } catch (e: Exception) {
                        notifyStubsFailure(e)
                    }
                }
                if (downloadedStubs.isNotEmpty()) {
                    Notifications.Bus.notify(MayaNotifications.stubsReady(downloadedStubs.sorted().joinToString(", ")))
                }
            }
        }.queue()
    }

    private fun notifyStubsFailure(exception: Exception) {
        Notifications.Bus.notify(
            MayaNotifications.stubsOperationFailed(exception.message ?: exception.javaClass.simpleName)
        )
    }

    private fun applyStubsToSdk(sdk: Sdk, library: String?, version: String?) {
        ApplicationManager.getApplication().invokeAndWait(
            { PythonStubsManager.applyToSdk(sdk, library, version) },
            ModalityState.any()
        )
    }

    private fun missingStubs(): Set<Pair<String, String>> {
        return mySdkPanel.data.mapNotNull { sdkInfo ->
            val library = sdkInfo.stubsLibrary
            val version = sdkInfo.stubsVersion
            if (library != null && version != null && !PythonStubsManager.isDownloaded(library, version)) {
                library to version
            } else {
                null
            }
        }.toSet()
    }

    private fun updateDownloadMissingStubsButton() {
        downloadMissingStubsButton.isVisible = missingStubs().isNotEmpty()
        downloadMissingStubsButton.isEnabled = downloadMissingStubsButton.isVisible
    }

    private fun downloadMissingStubs() {
        val missing = mySdkPanel.data.mapNotNull { sdkInfo ->
            val library = sdkInfo.stubsLibrary
            val version = sdkInfo.stubsVersion
            if (library != null && version != null && !PythonStubsManager.isDownloaded(library, version)) {
                sdkInfo.copy()
            } else {
                null
            }
        }
        if (missing.isEmpty()) {
            updateDownloadMissingStubsButton()
            return
        }

        downloadMissingStubsButton.isEnabled = false
        object : Task.Backgroundable(project, Loc.message("mayarecharm.stubs.OperationTitle")) {
            override fun run(indicator: ProgressIndicator) {
                val downloadedStubs = mutableSetOf<String>()
                for (sdkInfo in missing) {
                    val library = sdkInfo.stubsLibrary ?: continue
                    val version = sdkInfo.stubsVersion ?: continue
                    try {
                        indicator.text = "$library $version"
                        val wasDownloaded = PythonStubsManager.isDownloaded(library, version)
                        PythonStubsManager.ensureDownloaded(library, version)
                        val sdk = settings.findByPath(sdkInfo.mayaPyPath)
                            ?: throw IllegalStateException("Unable to find SDK: ${sdkInfo.mayaPyPath}")
                        applyStubsToSdk(sdk, library, version)
                        if (!wasDownloaded) downloadedStubs.add("$library $version")
                    } catch (e: Exception) {
                        notifyStubsFailure(e)
                    }
                }
                ApplicationManager.getApplication().invokeLater({
                    mySdkPanel.refreshStubsDisplay()
                    updateDownloadMissingStubsButton()
                    if (downloadedStubs.isNotEmpty()) {
                        Notifications.Bus.notify(
                            MayaNotifications.stubsReady(downloadedStubs.sorted().joinToString(", "))
                        )
                    }
                }, ModalityState.any())
            }
        }.queue()
    }
}
