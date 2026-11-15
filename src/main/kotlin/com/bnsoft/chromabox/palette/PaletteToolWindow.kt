package com.bnsoft.chromabox.palette

import com.bnsoft.chromabox.ChromaboxBundle
import com.bnsoft.chromabox.ChromaboxSettingsListener
import com.bnsoft.chromabox.ColorSwatchRenderer
import com.bnsoft.chromabox.HexColorParser
import com.bnsoft.chromabox.color.ChromaColor
import com.intellij.ide.util.scopeChooser.ScopeChooserCombo
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
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
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
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
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import org.jetbrains.concurrency.CancellablePromise

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
    private val model = CollectionListModel<PaletteEntry>()
    internal val list = JBList(model)
    private val scopeBox = ComboBox(PaletteScope.entries.toTypedArray())
    private val sortBox = ComboBox(PaletteSort.entries.toTypedArray())
    private val customScope = ScopeChooserCombo(project, false, false, null)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var stale = true
    private var scanning = false

    // Each refresh's number; partial results of an older one are dropped.
    private var generation = 0

    /** Replaces the scope chosen in [customScope]; for tests. */
    internal var customScopeOverride: GlobalSearchScope? = null

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

    init {
        scopeBox.renderer = SimpleListCellRenderer.create("") { ChromaboxBundle.message("palette.scope.${it.name}") }
        sortBox.renderer = SimpleListCellRenderer.create("") { ChromaboxBundle.message("palette.sort.${it.name}") }
        scopeBox.addActionListener {
            customScope.isVisible = scope == PaletteScope.CUSTOM
            scheduleRefresh(0)
        }
        customScope.isVisible = false
        customScope.comboBox.addItemListener { if (scope == PaletteScope.CUSTOM) scheduleRefresh(0) }
        Disposer.register(this, customScope)
        sortBox.addActionListener { show(model.items) }
        val bar = JPanel(HorizontalLayout(JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(4, 8)
            add(scopeBox)
            add(customScope)
            add(JBLabel(ChromaboxBundle.message("palette.sort")))
            add(sortBox)
        }
        toolbar = bar

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = EntryRenderer()
        setContent(ScrollPaneFactory.createScrollPane(list, true))
        PopupHandler.installPopupMenu(list, actions(), "ChromaboxPalette")
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                list.selectedValue?.let(::open) ?: return false
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
        PaletteScope.CUSTOM -> customScopeOverride ?: when (val chosen = customScope.selectedScope) {
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
            if (searchScope == null) {
                scanning = false
                show(emptyList())
                return null
            }
            ReadAction.nonBlocking<List<PaletteEntry>> { indexed(searchScope, current) }.inSmartMode(project)
        } else {
            val files = files()
            ReadAction.nonBlocking<List<PaletteEntry>> {
                val documents = FileDocumentManager.getInstance()
                Palette.entries(files.filter { it.isValid }.flatMap { file ->
                    documents.getDocument(file)?.let { Palette.scan(file, it, project) }.orEmpty()
                })
            }
        }
        scanning = true
        updateEmptyText()
        return action
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { entries ->
                if (current != generation) return@finishOnUiThread
                scanning = false
                show(entries)
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
                val partial = Palette.entries(byColor.values.flatten())
                ApplicationManager.getApplication().invokeLater({
                    if (current == generation && scanning) show(partial)
                }, ModalityState.any())
            }
            true
        }
        return Palette.entries(byColor.values.flatten())
    }

    /** Shows [entries] in the chosen order, keeping the selected color selected. */
    private fun show(entries: List<PaletteEntry>) {
        val selected = list.selectedValue?.hex
        model.replaceAll(Palette.sorted(entries, sort))
        val index = model.items.indexOfFirst { it.hex == selected }
        if (index >= 0) list.selectedIndex = index
        updateEmptyText()
    }

    private fun updateEmptyText() {
        list.emptyText.text = when {
            scanning && scope.indexed && DumbService.isDumb(project) -> ChromaboxBundle.message("palette.empty.indexing")
            scanning -> ChromaboxBundle.message("palette.empty.scanning")
            scope == PaletteScope.CUSTOM && searchScope() == null -> ChromaboxBundle.message("palette.empty.noScope")
            scope == PaletteScope.CURRENT_FILE && files().isEmpty() -> ChromaboxBundle.message("palette.empty.noFile")
            else -> ChromaboxBundle.message("palette.empty.${scope.name}")
        }
    }

    // Test hook.
    internal val entries: List<PaletteEntry> get() = model.items

    /** One place: go there. Several: list them. */
    private fun open(entry: PaletteEntry) {
        val only = entry.occurrences.singleOrNull()
        if (only != null) OpenFileDescriptor(project, only.file, only.range.startOffset).navigate(true)
        else PaletteActions.showUsages(project, entry)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        sink[ENTRY] = list.selectedValue
    }

    override fun dispose() = Unit

    private fun actions(): ActionGroup = DefaultActionGroup(
        EntryAction("palette.action.findUsages") { e, entry -> e.project?.let { PaletteActions.showUsages(it, entry) } },
        CopyAsGroup(),
        Separator.getInstance(),
        EntryAction("palette.action.replace") { e, entry -> replace(e.project ?: return@EntryAction, entry) },
    )

    private fun replace(project: Project, entry: PaletteEntry) {
        val title = ChromaboxBundle.message("palette.replace.title", entry.hex)
        val picked = ColorChooserService.getInstance().showDialog(project, list, title, entry.color.toAwt(), true, emptyList(), true)
            ?: return
        val files = entry.occurrences.map { it.file }.distinct()
        val result = PaletteActions.replace(project, files, entry.hex, ChromaColor.fromAwt(picked))
        PaletteActions.report(project, result)
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

    private class EntryRenderer : ColoredListCellRenderer<PaletteEntry>() {
        override fun customizeCellRenderer(list: JList<out PaletteEntry>, value: PaletteEntry, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = ColorSwatchRenderer.of(value.color)
            append(value.hex)
            append("  " + ChromaboxBundle.message("palette.uses", value.occurrences.size), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val formats = value.formats.joinToString(", ") { ChromaboxBundle.message("palette.format.${it.name}") }
            if (formats.isNotEmpty()) append("  $formats", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    companion object {
        val ENTRY = DataKey.create<PaletteEntry>("Chromabox.PaletteEntry")

        private const val REFRESH_DELAY_MS = 300
        private const val PARTIAL_INTERVAL_MS = 200
    }
}
