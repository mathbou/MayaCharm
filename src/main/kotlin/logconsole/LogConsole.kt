package logconsole

import com.intellij.diagnostic.logging.DefaultLogFilterModel
import com.intellij.diagnostic.logging.IndependentLogFilter
import com.intellij.diagnostic.logging.LogFilter
import com.intellij.diagnostic.logging.LogConsoleBase
import com.intellij.diagnostic.logging.LogConsolePreferences
import com.intellij.execution.actions.ClearConsoleAction
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mayacomms.MayaCommandInterface
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.Arrays
import java.util.LinkedHashSet
import kotlin.time.Duration.Companion.milliseconds
import MayaBundle as Loc

enum class MayaLogSeverity {
    HISTORY,
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

private val LEVEL_5_REGEX = Regex("(?<=- )\\b(level\\s*5|history)\\b(?= -)", RegexOption.IGNORE_CASE)


class MayaLogFilterModel(project: Project, tabId: String) : DefaultLogFilterModel(project) {
    private var previousSeverity: MayaLogSeverity? = null
    private val selectedFilterKey = "$SELECTED_FILTER_KEY_PREFIX.$tabId"
    private val selectedSeverities: MutableSet<MayaLogSeverity> = loadPersistedSeverities(selectedFilterKey)

    init {
        isCheckStandartFilters = false
    }

    override fun getLogFilters(): List<LogFilter> {
        val filters = mutableListOf<LogFilter>()
        filters.add(ToggleAllFilter("All"))
        filters.add(SeverityFilter("History", MayaLogSeverity.HISTORY))
        filters.add(SeverityFilter("Debug", MayaLogSeverity.DEBUG))
        filters.add(SeverityFilter("Infos", MayaLogSeverity.INFO))
        filters.add(SeverityFilter("Warnings", MayaLogSeverity.WARNING))
        filters.add(SeverityFilter("Errors", MayaLogSeverity.ERROR))
        filters.addAll(preferences.registeredLogFilters)
        return filters
    }

    override fun processLine(line: String): MyProcessingResult {
        val detectedSeverity = detectSeverity(line)
        val effectiveSeverity = detectedSeverity ?: previousSeverity

        if (detectedSeverity != null) {
            previousSeverity = detectedSeverity
        }

        val contentType = if (effectiveSeverity == MayaLogSeverity.ERROR) {
            ProcessOutputTypes.STDERR
        }
        else {
            ProcessOutputTypes.STDOUT
        }

        val isVisible = super.isApplicable(line) && isVisibleForSelectedFilter(effectiveSeverity)
        return MyProcessingResult(contentType, isVisible, null)
    }

    override fun isFilterSelected(filter: LogFilter): Boolean {
        // LogConsoleBase.resetLogFilter() programmatically selects the first "selected" filter,
        // which would call selectFilter() and toggle it off. We rely on in-item checkmarks instead.
        return false
    }

    private fun isVisibleForSelectedFilter(severity: MayaLogSeverity?): Boolean {
        severity ?: return true
        return severity in selectedSeverities
    }

    fun detectSeverity(line: String): MayaLogSeverity? {
        if (LEVEL_5_REGEX.containsMatchIn(line)) {
            return MayaLogSeverity.HISTORY
        }

        return when (LogConsolePreferences.getType(line)) {
            LogConsolePreferences.ERROR -> MayaLogSeverity.ERROR
            LogConsolePreferences.WARNING -> MayaLogSeverity.WARNING
            LogConsolePreferences.INFO -> MayaLogSeverity.INFO
            LogConsolePreferences.DEBUG -> MayaLogSeverity.DEBUG
            else -> null
        }
    }

    private inner class SeverityFilter(
        private val label: String,
        private val severity: MayaLogSeverity
    ) : IndependentLogFilter(label) {
        override fun getName(): String {
            return displayName(label, selectedSeverities.contains(severity))
        }

        override fun toString(): String {
            return name
        }

        override fun selectFilter() {
            if (!selectedSeverities.add(severity)) {
                selectedSeverities.remove(severity)
            }
            persistFilters(selectedFilterKey, selectedSeverities)
        }

        override fun isSelected(): Boolean {
            return selectedSeverities.contains(severity)
        }

        override fun isAcceptable(line: String): Boolean {
            return true
        }
    }

    private inner class ToggleAllFilter(private val label: String) : IndependentLogFilter(label) {
        override fun getName(): String {
            return displayName(label, allSeveritiesSelected())
        }

        override fun toString(): String {
            return name
        }

        override fun selectFilter() {
            if (allSeveritiesSelected()) {
                selectedSeverities.clear()
            }
            else {
                selectedSeverities.clear()
                selectedSeverities.addAll(ALL_SEVERITIES)
            }
            persistFilters(selectedFilterKey, selectedSeverities)
        }

        override fun isSelected(): Boolean {
            return allSeveritiesSelected()
        }

        override fun isAcceptable(line: String): Boolean {
            return true
        }
    }

    private fun allSeveritiesSelected(): Boolean {
        return selectedSeverities.containsAll(ALL_SEVERITIES)
    }

    private companion object {
        private const val SELECTED_FILTER_KEY_PREFIX = "mayarecharm.logconsole.selectedFilter"

        private val DEFAULT_SELECTED_SEVERITIES = linkedSetOf(
            MayaLogSeverity.INFO,
            MayaLogSeverity.WARNING,
            MayaLogSeverity.ERROR
        )
        private val ALL_SEVERITIES = linkedSetOf(
            MayaLogSeverity.HISTORY,
            MayaLogSeverity.DEBUG,
            MayaLogSeverity.INFO,
            MayaLogSeverity.WARNING,
            MayaLogSeverity.ERROR
        )

        private fun loadPersistedSeverities(selectedFilterKey: String): MutableSet<MayaLogSeverity> {
            val properties = PropertiesComponent.getInstance()
            val persistedValue = properties.getValue(selectedFilterKey)

            if (persistedValue.isNullOrBlank()) {
                return LinkedHashSet(DEFAULT_SELECTED_SEVERITIES)
            }

            val severities = persistedValue
                .split(',')
                .map { entry -> entry.trim() }
                .mapNotNull { entry -> MayaLogSeverity.entries.firstOrNull { it.name == entry } }
                .toCollection(LinkedHashSet())

            return if (severities.isEmpty()) {
                LinkedHashSet(DEFAULT_SELECTED_SEVERITIES)
            }
            else {
                severities
            }
        }

        private fun persistFilters(selectedFilterKey: String, selectedSeverities: Set<MayaLogSeverity>) {
            val persistedValue = MayaLogSeverity.entries
                .filter { severity -> selectedSeverities.contains(severity) }
                .joinToString(",") { severity -> severity.name }
            PropertiesComponent.getInstance().setValue(selectedFilterKey, persistedValue)
        }

        private fun displayName(label: String, selected: Boolean): String {
            return if (selected) "✔ $label" else "    $label"
        }
    }
}

class ClearConsoleAndLogFileAction(private val logConsole: LogConsole) :
    ClearConsoleAction() {

    override fun actionPerformed(e: AnActionEvent) {
        super.actionPerformed(e)
        logConsole.clearLogFile()
    }
}

class ConnectToMayaLogAction(
    private val logConsole: LogConsole,
    private val port: Int
) : AnAction(
    Loc.message("mayarecharm.action.ConnectLogText"),
    Loc.message("mayarecharm.action.ConnectLogDescription"),
    IconLoader.getIcon("/icons/MayaReCharm_link16.png", ConnectToMayaLogAction::class.java)
) {

    override fun actionPerformed(e: AnActionEvent) {
        MayaCommandInterface(port).connectMayaLog()
        logConsole.reloadLogFile()
    }
}

class SendBufferLogAction(
    private val port: Int
): AnAction(
    Loc.message("mayarecharm.action.SendDocumentText"),
    Loc.message("mayarecharm.action.SendDocumentDescription"),
    IconLoader.getIcon("/icons/MayaReCharm_Action.png", SendBufferLogAction::class.java)
) {
    override fun actionPerformed(e: AnActionEvent) {
        val data = getTargetFile(e) ?: return

        val fileTypeManager = FileTypeManager.getInstance()
        val pyFileType = fileTypeManager.findFileTypeByName("Python")
        if (pyFileType == null || !fileTypeManager.isFileOfType(data, pyFileType)) {
            return
        }

        val docManager = FileDocumentManager.getInstance()
        docManager.getDocument(data)?.also { docManager.saveDocument(it) }

        MayaCommandInterface(port).sendFileToMaya(data.path)
    }

    private fun getTargetFile(e: AnActionEvent): VirtualFile? {
        return e.getData(LangDataKeys.VIRTUAL_FILE)
            ?: e.project?.let { FileEditorManager.getInstance(it) }?.selectedFiles?.firstOrNull()
    }
}


class LogConsole(
    project: Project,
    private val file: File,
    private val port: Int,
    private val charset: Charset,
    skippedContents: Long,
    title: String,
    buildInActions: Boolean,
    searchScope: GlobalSearchScope?
) : LogConsoleBase(
    project,
    getReader(file, charset, skippedContents),
    title,
    buildInActions,
    MayaLogFilterModel(project, port.toString()),
    searchScope ?: GlobalSearchScope.allScope(project)
) {

    private val path = file.absolutePath
    private var oldSnapshot = FileSnapshot()
    private val refilterScrollScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refilterScrollJob: Job? = null

    init {
        Disposer.register(this) {
            refilterScrollScope.cancel()
        }
    }

    override fun getTooltip(): String {
        return path
    }

    override fun updateReaderIfNeeded(reader: BufferedReader?): BufferedReader? {
        reader ?: return null

        val snapshot = FileSnapshot()
        if (oldSnapshot.rolloverDetected(snapshot)) {
            reader.close()
            oldSnapshot = snapshot
            return BufferedReader(InputStreamReader(Files.newInputStream(file.toPath()), charset))
        }

        oldSnapshot = snapshot
        return reader
    }

    override fun getFilterModel(): MayaLogFilterModel? {
        return super.getFilterModel() as MayaLogFilterModel?
    }

    private val DARK_WARNING_COLOR_CODE = 93
    private val DARK_HISTORY_COLOR_CODE = 95
    private val DARK_DEBUG_COLOR_CODE = 96
    private val LIGHT_WARNING_COLOR_CODE = 33
    private val LIGHT_HISTORY_COLOR_CODE = 35
    private val LIGHT_DEBUG_COLOR_CODE = 36

    private fun ansiColorCodeFor(severity: MayaLogSeverity?): Int? {
        val isDarkTheme = EditorColorsManager.getInstance().isDarkEditor

        return when (severity) {
            MayaLogSeverity.WARNING -> if (isDarkTheme) DARK_WARNING_COLOR_CODE else LIGHT_WARNING_COLOR_CODE
            MayaLogSeverity.HISTORY -> if (isDarkTheme) DARK_HISTORY_COLOR_CODE else LIGHT_HISTORY_COLOR_CODE
            MayaLogSeverity.DEBUG -> if (isDarkTheme) DARK_DEBUG_COLOR_CODE else LIGHT_DEBUG_COLOR_CODE
            else -> null
        }
    }

    fun normalizeLineForDisplay(line: String, severity: MayaLogSeverity?): String {
        val normalizedLine = LEVEL_5_REGEX.replaceFirst(line, MayaLogSeverity.HISTORY.toString())
        val colorCode = ansiColorCodeFor(severity)

        return if (colorCode != null) {
            "\u001B[${colorCode}m$normalizedLine\u001B[0m"
        }
        else {
            normalizedLine
        }
    }

    private var previousSeverity: MayaLogSeverity? = null

    override fun addMessage(text: String?) {
        val rawMessage = text ?: ""

        val detectedSeverity = filterModel?.detectSeverity(rawMessage)
        val effectiveSeverity = detectedSeverity ?: previousSeverity
        if (detectedSeverity != null) {
            previousSeverity = detectedSeverity
        }

        val msg = normalizeLineForDisplay(rawMessage, effectiveSeverity)
        super.addMessage(msg)
    }

    override fun onFilterStateChange(filter: LogFilter) {
        super.onFilterStateChange(filter)
        keepScrollAtBottomAfterRefilter()
    }

    override fun onTextFilterChange() {
        super.onTextFilterChange()
        keepScrollAtBottomAfterRefilter()
    }

    private fun keepScrollAtBottomAfterRefilter() {
        refilterScrollJob?.cancel()
        refilterScrollJob = refilterScrollScope.launch {
            delay(30.milliseconds)
            ApplicationManager.getApplication().invokeLater {
                consoleNotNull.requestScrollingToEnd()
            }
        }
    }

    override fun isActive(): Boolean {
        return true
    }

    fun clearLogFile() {
        file.writeText("")
    }

    fun reloadLogFile() {
        activate()
    }

    override fun getOrCreateActions(): ActionGroup {
        val group = DefaultActionGroup()

        group.addAction(SendBufferLogAction(port), Constraints.FIRST)
        group.addAction(ConnectToMayaLogAction(this, port))
        group.addSeparator()

        val superGroup = super.getOrCreateActions() as DefaultActionGroup
        group.addAll(superGroup.childActionsOrStubs.toList())

        val clearAllAction = ClearConsoleAndLogFileAction(this)
        val originalClearAllAction: AnAction = group.childActionsOrStubs.last { it is ClearConsoleAction }
        group.replaceAction(originalClearAllAction, clearAllAction)

        return group as ActionGroup
    }

    private inner class FileSnapshot {
        val length: Long = file.length()
        val firstBytes: ByteArray = ByteArray(20)

        init {
            try {
                Files.newInputStream(file.toPath()).use { stream ->
                    stream.read(firstBytes)
                }
            }
            catch (_: Exception) {
            }
        }

        fun rolloverDetected(current: FileSnapshot): Boolean {
            return current.length < length || !firstBytes.contentEquals(current.firstBytes)
        }
    }

    companion object {
        private fun getReader(file: File, charset: Charset, skippedContents: Long): Reader? {
            return try {
                try {
                    val inputStream = Files.newInputStream(file.toPath())
                    if (file.length() >= skippedContents) {
                        var skipped = 0L
                        while (skipped < skippedContents) {
                            skipped += inputStream.skip(skippedContents - skipped)
                        }
                    }
                    BufferedReader(InputStreamReader(inputStream, charset))
                } catch (_: FileNotFoundException) {
                    if (FileUtilRt.createIfNotExists(file)) {
                        BufferedReader(InputStreamReader(Files.newInputStream(file.toPath()), charset))
                    } else {
                        null
                    }
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
