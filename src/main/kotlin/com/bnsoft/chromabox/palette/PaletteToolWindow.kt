package com.bnsoft.chromabox.palette

import com.bnsoft.chromabox.ChromaboxBundle
import com.bnsoft.chromabox.ChromaboxSettingsListener
import com.bnsoft.chromabox.ColorSwatchRenderer
import com.bnsoft.chromabox.color.ChromaColor
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
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
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
import org.jetbrains.concurrency.CancellablePromise
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** Which files the palette lists colors of (C-03.1). Project and custom scopes come with the index. */
enum class PaletteScope { CURRENT_FILE, OPEN_FILES }

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
 * background after edits, file switches and settings changes, and only while [isToolWindowVisible] (C-03.8).
 */
class PalettePanel(private val project: Project, private val isToolWindowVisible: () -> Boolean) :
    SimpleToolWindowPanel(true, true), Disposable, UiDataProvider {
    private val model = CollectionListModel<PaletteEntry>()
    internal val list = JBList(model)
    private val scopeBox = ComboBox(PaletteScope.entries.toTypedArray())
    private val sortBox = ComboBox(PaletteSort.entries.toTypedArray())
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var stale = true
    private var scanning = false

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
        scopeBox.addActionListener { scheduleRefresh(0) }
        sortBox.addActionListener { show(model.items) }
        val bar = JPanel(HorizontalLayout(JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(4, 8)
            add(scopeBox)
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
                if (file in files()) scheduleRefresh()
            }
        }, this)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(ChromaboxSettingsListener.TOPIC, ChromaboxSettingsListener { scheduleRefresh(0) })
        scheduleRefresh(0)
    }

    private fun files(): List<VirtualFile> {
        val editors = FileEditorManager.getInstance(project)
        return when (scope) {
            PaletteScope.CURRENT_FILE -> editors.selectedFiles.take(1)
            PaletteScope.OPEN_FILES -> editors.openFiles.toList()
        }
    }

    private fun scheduleRefresh(delayMillis: Int = REFRESH_DELAY_MS) {
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh() }, delayMillis)
    }

    fun refreshIfStale() {
        if (stale) scheduleRefresh(0)
    }

    /** Scans [files] in a background read action; null while hidden, which leaves the palette to catch up when shown. */
    internal fun refresh(): CancellablePromise<*>? {
        if (!isToolWindowVisible()) {
            stale = true
            return null
        }
        stale = false
        scanning = true
        updateEmptyText()
        val files = files()
        return ReadAction.nonBlocking<List<PaletteEntry>> {
            val documents = FileDocumentManager.getInstance()
            Palette.entries(files.filter { it.isValid }.flatMap { file ->
                documents.getDocument(file)?.let { Palette.scan(file, it, project) }.orEmpty()
            })
        }
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { entries ->
                scanning = false
                show(entries)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
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
            scanning -> ChromaboxBundle.message("palette.empty.scanning")
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
        val result = PaletteActions.replace(project, entry.occurrences, ChromaColor.fromAwt(picked))
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
    }
}
