package com.bnsoft.chromabox.palette

import com.bnsoft.chromabox.ChromaboxBundle
import com.bnsoft.chromabox.ChromaboxConfigurable
import com.bnsoft.chromabox.ChromaboxSettings
import com.bnsoft.chromabox.ChromaboxSettingsListener
import com.bnsoft.chromabox.ColorSwatchRenderer
import com.bnsoft.chromabox.HexColorParser
import com.bnsoft.chromabox.color.ChromaColor
import com.intellij.icons.AllIcons
import com.intellij.ide.util.scopeChooser.ScopeChooserCombo
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColorChooserService
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.io.IOException
import java.util.Locale
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import org.jetbrains.concurrency.CancellablePromise

/** A line of the palette: [entry], in [group] if near-duplicates are grouped; [distance] from the leader for a member. */
class PaletteRow(val entry: PaletteEntry, val group: PaletteGroup?, val distance: Double?)

/** Which files the palette lists colors of (C-03.1). The current and open files are scanned; the others use the index. */
enum class PaletteScope(val indexed: Boolean = false) { CURRENT_FILE, OPEN_FILES, PROJECT(indexed = true), CUSTOM(indexed = true) }

class PaletteToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PalettePanel(project) { toolWindow.isVisible }
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        // While hidden, it doesn't scan (C-03.8); it catches up when shown.
        project.messageBus.connect(panel).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(shown: ToolWindow) {
                if (shown.id == toolWindow.id) panel.refreshIfStale()
            }
        })
    }
}

/**
 * The colors of the files in [scope], with how often and in which formats each is written (C-03.3). Scans in the
 * background after edits, file switches and settings changes, and only while [isToolWindowVisible] (C-03.8). Project
 * and custom scopes read the index once indexing is done, and show colors as they're found.
 */
class PalettePanel(private val project: Project, private val isToolWindowVisible: () -> Boolean) :
    SimpleToolWindowPanel(true, true), Disposable, UiDataProvider {
    private val model = CollectionListModel<PaletteRow>()
    internal val list = JBList(model)
    private val groupBox = JBCheckBox(ChromaboxBundle.message("palette.groupSimilar"), true)

    // The groups found by the last refresh, in no particular order.
    private var groups: List<PaletteGroup> = emptyList()
    private val scopeBox = ComboBox(PaletteScope.entries.toTypedArray())
    private val sortBox = ComboBox(PaletteSort.entries.toTypedArray())
    private val customScope = ScopeChooserCombo(project, false, false, null)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var stale = true
    private var scanning = false

    // Each refresh's number; partial results of an older one are dropped.
    private var generation = 0

    /** Replaces the scope chosen in [customScope], which fills in asynchronously; for tests. Returns null for none. */
    internal var customScopeOverride: (() -> GlobalSearchScope?)? = null

    internal var scope: PaletteScope
        get() = scopeBox.item
        set(value) {
            scopeBox.item = value
        }

    internal var sort: PaletteSort
        get() = sortBox.item
        set(value) {
            sortBox.item = value
        }

    internal var groupSimilar: Boolean
        get() = groupBox.isSelected
        set(value) {
            groupBox.isSelected = value
        }

    init {
        scopeBox.renderer = textListCellRenderer { ChromaboxBundle.message("palette.scope.${it.name}") }
        sortBox.renderer = textListCellRenderer { ChromaboxBundle.message("palette.sort.${it.name}") }
        scopeBox.addActionListener {
            customScope.isVisible = scope == PaletteScope.CUSTOM
            scheduleRefresh(0)
        }
        customScope.isVisible = false
        customScope.comboBox.addItemListener { if (scope == PaletteScope.CUSTOM) scheduleRefresh(0) }
        Disposer.register(this, customScope)
        sortBox.addActionListener { show(groups) }
        groupBox.addItemListener { show(groups) }
        // Wraps in a narrow tool window, so nothing is cut off.
        val bar = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(4, 8)
            add(scopeBox)
            add(customScope)
            add(JBLabel(ChromaboxBundle.message("palette.sort")))
            add(sortBox)
            add(groupBox)
        }
        val exportToolbar = ActionManager.getInstance().createActionToolbar("ChromaboxPaletteExport", DefaultActionGroup(ExportGroup()), true)
        exportToolbar.targetComponent = this
        exportToolbar.component.border = JBUI.Borders.empty()
        exportToolbar.component.isOpaque = false
        bar.add(exportToolbar.component)
        toolbar = bar

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = RowRenderer()
        setContent(ScrollPaneFactory.createScrollPane(list, true))
        PopupHandler.installPopupMenu(list, actions(), "ChromaboxPalette")
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                list.selectedValue?.entry?.let(::open) ?: return false
                return true
            }
        }.installOn(list)

        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                if (scope == PaletteScope.CURRENT_FILE) scheduleRefresh(0)
            }

            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                if (scope == PaletteScope.OPEN_FILES) scheduleRefresh(0)
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (scope == PaletteScope.OPEN_FILES) scheduleRefresh(0)
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (inScope(file)) scheduleRefresh()
            }
        }, this)
        // Files created, deleted or changed outside the editor.
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (scope.indexed) scheduleRefresh()
            }
        })
        project.messageBus.connect(this).subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun enteredDumbMode() = updateEmptyText()

            override fun exitDumbMode() = updateEmptyText()
        })
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(ChromaboxSettingsListener.TOPIC, ChromaboxSettingsListener { scheduleRefresh(0) })
        scheduleRefresh(0)
    }

    /** The files of a scanned scope. */
    private fun files(): List<VirtualFile> {
        val editors = FileEditorManager.getInstance(project)
        return when (scope) {
            PaletteScope.CURRENT_FILE -> editors.selectedFiles.take(1)
            PaletteScope.OPEN_FILES -> editors.openFiles.toList()
            PaletteScope.PROJECT, PaletteScope.CUSTOM -> emptyList()
        }
    }

    /** The search scope of an indexed scope; null while no custom scope is chosen. */
    private fun searchScope(): GlobalSearchScope? = when (scope) {
        PaletteScope.PROJECT -> GlobalSearchScope.projectScope(project)
        PaletteScope.CUSTOM -> customScopeOverride?.let { return it() } ?: when (val chosen = customScope.selectedScope) {
            is GlobalSearchScope -> chosen
            is LocalSearchScope -> GlobalSearchScope.filesScope(project, chosen.virtualFiles.toList())
            else -> null
        }
        PaletteScope.CURRENT_FILE, PaletteScope.OPEN_FILES -> null
    }

    private fun inScope(file: VirtualFile) = if (scope.indexed) searchScope()?.contains(file) == true else file in files()

    private fun scheduleRefresh(delayMillis: Int = REFRESH_DELAY_MS) {
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh() }, delayMillis)
    }

    fun refreshIfStale() {
        if (stale) scheduleRefresh(0)
    }

    /**
     * Finds the colors in a background read action; null while hidden, which leaves the palette to catch up when shown,
     * and when there's no scope to look in.
     */
    internal fun refresh(): CancellablePromise<*>? {
        // This one covers any that's scheduled.
        alarm.cancelAllRequests()
        if (!isToolWindowVisible()) {
            stale = true
            return null
        }
        stale = false
        val current = ++generation
        val action = if (scope.indexed) {
            val searchScope = searchScope()
            if (searchScope == null || !ColorIndex.enabled) {
                scanning = false
                show(emptyList())
                return null
            }
            ReadAction.nonBlocking<List<PaletteGroup>> { grouped(indexed(searchScope, current)) }.inSmartMode(project)
        } else {
            val files = files()
            ReadAction.nonBlocking<List<PaletteGroup>> {
                val documents = FileDocumentManager.getInstance()
                grouped(Palette.entries(files.filter { it.isValid }.flatMap { file ->
                    documents.getDocument(file)?.let { Palette.scan(file, it, project) }.orEmpty()
                }))
            }
        }
        scanning = true
        updateEmptyText()
        return action
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { found ->
                if (current != generation) return@finishOnUiThread
                scanning = false
                show(found)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** The colors in [searchScope], from the index; shows what's found so far every [PARTIAL_INTERVAL_MS] (C-03.8). */
    private fun indexed(searchScope: GlobalSearchScope, current: Int): List<PaletteEntry> {
        val byColor = LinkedHashMap<String, MutableList<PaletteOccurrence>>()
        var shown = System.currentTimeMillis()
        ColorIndex.process(project, searchScope) { hex, file, colors ->
            ProgressManager.checkCanceled()
            val color = HexColorParser.parse(hex)?.let(ChromaColor::fromAwt) ?: return@process true
            byColor.getOrPut(hex) { ArrayList() } += colors.map { PaletteOccurrence(file, TextRange(it.start, it.end), color, it.format) }
            if (System.currentTimeMillis() - shown > PARTIAL_INTERVAL_MS) {
                shown = System.currentTimeMillis()
                val partial = grouped(Palette.entries(byColor.values.flatten()))
                ApplicationManager.getApplication().invokeLater({
                    if (current == generation && scanning) show(partial)
                }, ModalityState.any())
            }
            true
        }
        return Palette.entries(byColor.values.flatten())
    }

    /** Groups near-duplicates (C-03.4), with the threshold from settings; in the background, it's quadratic. */
    private fun grouped(entries: List<PaletteEntry>): List<PaletteGroup> =
        Palette.groups(entries, ChromaboxSettings.getInstance().state.nearDuplicateThreshold)

    /**
     * Shows [found] in the chosen order, keeping the selected color selected: with [groupSimilar], each group's members
     * under its leader; otherwise every color on its own.
     */
    private fun show(found: List<PaletteGroup>) {
        groups = found
        val selected = list.selectedValue?.entry?.hex
        val rows = if (groupSimilar) {
            Palette.sortedGroups(found, sort).flatMap { group ->
                listOf(PaletteRow(group.leader, group, null)) + group.members.mapIndexed { i, member -> PaletteRow(member, group, group.distances[i]) }
            }
        } else {
            Palette.sorted(found.flatMap { it.entries }, sort).map { PaletteRow(it, null, null) }
        }
        model.replaceAll(rows)
        val index = rows.indexOfFirst { it.entry.hex == selected }
        if (index >= 0) list.selectedIndex = index
        updateEmptyText()
    }

    private fun updateEmptyText() {
        if (scope.indexed && !ColorIndex.enabled) {
            list.emptyText.text = ChromaboxBundle.message("palette.empty.indexOff")
            list.emptyText.appendLine(ChromaboxBundle.message("palette.empty.indexOff.turnOn"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ChromaboxConfigurable::class.java)
            }
            return
        }
        list.emptyText.text = when {
            scanning && scope.indexed && DumbService.isDumb(project) -> ChromaboxBundle.message("palette.empty.indexing")
            scanning -> ChromaboxBundle.message("palette.empty.scanning")
            scope == PaletteScope.CUSTOM && searchScope() == null -> ChromaboxBundle.message("palette.empty.noScope")
            scope == PaletteScope.CURRENT_FILE && files().isEmpty() -> ChromaboxBundle.message("palette.empty.noFile")
            else -> ChromaboxBundle.message("palette.empty.${scope.name}")
        }
    }

    // Test hooks.
    internal val entries: List<PaletteEntry> get() = model.items.map { it.entry }
    internal val rows: List<PaletteRow> get() = model.items

    /** The listed colors as [format], in the order they're listed, each on its own. */
    internal fun exportText(format: PaletteExport): String {
        val name = ChromaboxBundle.message("palette.export.name", project.name, ChromaboxBundle.message("palette.scope.${scope.name}"))
        return format.export(entries, name)
    }

    /** Asks where to save the listed colors as [format], and saves them (C-03.7). */
    private fun export(format: PaletteExport) {
        val count = entries.size
        val descriptor = FileSaverDescriptor(
            ChromaboxBundle.message("palette.export.title"),
            ChromaboxBundle.message("palette.export.description", count),
            format.extension,
        )
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(project.guessProjectDir(), "${project.name}-colors.${format.extension}") ?: return
        val text = exportText(format)
        val notifications = NotificationGroupManager.getInstance().getNotificationGroup(PaletteActions.NOTIFICATIONS)
        try {
            val file = WriteAction.compute<VirtualFile?, IOException> {
                wrapper.getVirtualFile(true)?.also { it.setBinaryContent(text.toByteArray(Charsets.UTF_8)) }
            } ?: throw IOException(wrapper.file.path)
            notifications.createNotification(ChromaboxBundle.message("palette.export.done", count, file.presentableName), NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimpleExpiring(ChromaboxBundle.message("palette.export.open")) {
                    OpenFileDescriptor(project, file).navigate(true)
                })
                .notify(project)
        } catch (e: IOException) {
            notifications.createNotification(ChromaboxBundle.message("palette.export.failed", e.message.orEmpty()), NotificationType.ERROR)
                .notify(project)
        }
    }

    /** One item per export format; disabled while the list is empty. */
    private inner class ExportGroup : DefaultActionGroup(ChromaboxBundle.message("palette.export"), true), DumbAware {
        init {
            templatePresentation.icon = AllIcons.ToolbarDecorator.Export
            for (format in PaletteExport.entries) {
                add(object : DumbAwareAction(ChromaboxBundle.message("palette.export.${format.name}")) {
                    override fun actionPerformed(e: AnActionEvent) = export(format)
                })
            }
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !model.isEmpty
        }
    }

    /** One place: go there. Several: list them. */
    private fun open(entry: PaletteEntry) {
        val only = entry.occurrences.singleOrNull()
        if (only != null) OpenFileDescriptor(project, only.file, only.range.startOffset).navigate(true)
        else PaletteActions.showUsages(project, entry)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        val row = list.selectedValue
        sink[ENTRY] = row?.entry
        sink[GROUP] = row?.group?.takeIf { it.members.isNotEmpty() }
    }

    override fun dispose() = Unit

    private fun actions(): ActionGroup = DefaultActionGroup(
        EntryAction("palette.action.findUsages") { e, entry -> e.project?.let { PaletteActions.showUsages(it, entry) } },
        CopyAsGroup(),
        Separator.getInstance(),
        EntryAction("palette.action.replace") { e, entry -> replace(e.project ?: return@EntryAction, entry) },
        MergeAction(),
    )

    private fun replace(project: Project, entry: PaletteEntry) {
        val title = ChromaboxBundle.message("palette.replace.title", entry.hex)
        val picked = ColorChooserService.getInstance().showDialog(project, list, title, entry.color.toAwt(), true, emptyList(), true)
            ?: return
        val files = entry.occurrences.map { it.file }.distinct()
        val result = PaletteActions.replace(project, files, entry.hex, ChromaColor.fromAwt(picked))
        PaletteActions.report(project, result)
    }

    /** Merges the selected color's group of near-duplicates into one color (C-03.6). */
    private class MergeAction : DumbAwareAction(ChromaboxBundle.message("palette.action.merge")) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = e.getData(GROUP) != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val dialog = MergeDialog(project, e.getData(GROUP) ?: return)
            if (!dialog.showAndGet()) return
            PaletteActions.report(project, PaletteActions.apply(project, dialog.plan, ChromaboxBundle.message("palette.merge.command")))
        }
    }

    private class EntryAction(key: String, private val perform: (AnActionEvent, PaletteEntry) -> Unit) :
        DumbAwareAction(ChromaboxBundle.message(key)) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = e.getData(ENTRY) != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            perform(e, e.getData(ENTRY) ?: return)
        }
    }

    /** One item per notation, showing the text it copies. */
    private class CopyAsGroup : ActionGroup(ChromaboxBundle.message("palette.action.copyAs"), true), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val color = e?.getData(ENTRY)?.color ?: return EMPTY_ARRAY
            return CopyFormat.entries.map { format ->
                val text = format.format(color)
                object : DumbAwareAction() {
                    init {
                        templatePresentation.setText(text, false)
                    }

                    override fun actionPerformed(e: AnActionEvent) = CopyPasteManager.getInstance().setContents(StringSelection(text))
                }
            }.toTypedArray()
        }
    }

    private class RowRenderer : ColoredListCellRenderer<PaletteRow>() {
        // The renderer is reused, so a leader after a member needs its padding back.
        private val baseLeft = ipad.left

        override fun customizeCellRenderer(list: JList<out PaletteRow>, value: PaletteRow, index: Int, selected: Boolean, hasFocus: Boolean) {
            val entry = value.entry
            // Members sit under their leader.
            ipad.left = if (value.distance != null) baseLeft + JBUI.scale(MEMBER_INDENT) else baseLeft
            icon = ColorSwatchRenderer.of(entry.color)
            append(entry.hex)
            append("  " + ChromaboxBundle.message("palette.uses", entry.occurrences.size), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val formats = entry.formats.joinToString(", ") { ChromaboxBundle.message("palette.format.${it.name}") }
            if (formats.isNotEmpty()) append("  $formats", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            val group = value.group
            when {
                value.distance != null -> append("  " + ChromaboxBundle.message("palette.distance", String.format(Locale.ROOT, "%.1f", value.distance)), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                group != null && group.members.isNotEmpty() -> append("  " + ChromaboxBundle.message("palette.similar", group.members.size), SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            }
        }
    }

    companion object {
        val ENTRY = DataKey.create<PaletteEntry>("Chromabox.PaletteEntry")

        /** The selected color's group, if it has near-duplicates. */
        val GROUP = DataKey.create<PaletteGroup>("Chromabox.PaletteGroup")

        private const val MEMBER_INDENT = 20
        private const val REFRESH_DELAY_MS = 300
        private const val PARTIAL_INTERVAL_MS = 200
    }
}
