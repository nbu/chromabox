package com.bnsoft.chromabox.engine

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.concurrency.CancellablePromise
import java.awt.Cursor
import java.util.UUID
import javax.swing.SwingUtilities

/**
 * Shows the renderer of every [Lens] match in one editor as an inlay before the literal and, in
 * [LensPresentation.INLAY_ONLY], folds the literal away. Clicking an inlay opens the lens editor and writes the
 * edited value back.
 */
class EditorLensController private constructor(private val editor: Editor, private val host: LensHost) : Disposable {
    private class Element<V : Any>(
        val lens: Lens<V>,
        val match: LensMatch<V>,
        val marker: RangeMarker,
        val text: String,
        val inlay: Inlay<*>,
        var fold: FoldRegion? = null,
    ) {
        val key get() = Triple(lens.id, marker.textRange, text)
    }

    private val elements = mutableListOf<Element<*>>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** Text inserted since the last applied scan; a literal created there is being typed, so it isn't folded yet. */
    private val edited = mutableListOf<RangeMarker>()

    /** True while an Expand action runs in this editor: folds it expands were expanded by the user. */
    private var userExpanding = false

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) {
                // An edit that removes or replaces text of a folded literal (Backspace right after it, Delete right
                // before it) would change text the user can't see, so show it. Folding can't change during a
                // document change, so defer it; the caret is in the literal then, so it stays shown until it leaves.
                val removed = TextRange(event.offset, event.offset + event.oldLength)
                val touched = elements.mapNotNull { it.fold }
                    .filter { it.isValid && !it.isExpanded && it.textRange.intersectsStrict(removed) }
                if (touched.isEmpty()) return
                ApplicationManager.getApplication().invokeLater({
                    val folds = touched.filter { it.isValid && !it.isExpanded }
                    if (folds.isNotEmpty()) {
                        editor.foldingModel.runBatchFoldingOperation({ folds.forEach(::show) }, false, false)
                    }
                }, { editor.isDisposed })
            }

            override fun documentChanged(event: DocumentEvent) {
                edited += editor.document.createRangeMarker(event.offset, event.offset + event.newLength)
                    .apply { isGreedyToLeft = true; isGreedyToRight = true }
                scheduleRefresh()
            }
        }, this)
        editor.caretModel.addCaretListener(object : CaretListener {
            // Folding can't be changed from inside a caret event, so defer it.
            override fun caretPositionChanged(event: CaretEvent) {
                val caret = event.caret
                if (caret != null) {
                    val old = editor.logicalPositionToOffset(event.oldPosition)
                    if (elements.none { it.fold?.let { f -> f.isValid && old > f.startOffset && old < f.endOffset } == true }) {
                        caret.putUserData(OFFSET_BEFORE_FOLD, old)
                    }
                }
                ApplicationManager.getApplication().invokeLater({ updateFoldExpansion() }, { editor.isDisposed })
            }
        }, this)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mousePressed(event: EditorMouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event.mouseEvent)) return
                val element = elementAt(event)?.takeIf { it.isEditable } ?: return
                // Keep the caret where it is, so a folded literal doesn't unfold.
                event.consume()
                startEdit(element, RelativePoint(event.mouseEvent))
            }
        }, this)
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) {
                val editable = elementAt(event)?.isEditable == true
                val cursor = if (editable) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
                (editor as? EditorEx)?.setCustomCursor(this@EditorLensController, cursor)
            }
        }, this)
        (editor.foldingModel as? FoldingModelEx)?.addListener(object : FoldingListener {
            override fun onFoldRegionStateChange(region: FoldRegion) {
                if (userExpanding && region.isExpanded && region.getUserData(LENS_FOLD) == true) region.putUserData(SHOWN, true)
            }

            // The IDE restores saved folds in a batch of its own, possibly after the first scan, and unfolds the
            // caret's line when navigating to a file.
            override fun onFoldProcessingEnd() {
                if (orphanFolds().isEmpty() && foldsToCollapse().isEmpty()) return
                ApplicationManager.getApplication().invokeLater({
                    removeOrphanFolds()
                    updateFoldExpansion()
                }, { editor.isDisposed })
            }
        }, this)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(AnActionListener.TOPIC, object : AnActionListener {
            override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
                userExpanding = ActionManager.getInstance().getId(action)?.startsWith("Expand") == true &&
                    event.getData(CommonDataKeys.EDITOR) == editor
            }

            override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: AnActionResult) {
                userExpanding = false
            }
        })
        host.subscribe(this) {
            clear()
            scheduleRefresh(0)
        }
    }

    /**
     * Folds the engine created in an earlier session. When an editor closes, the IDE saves its fold regions and
     * restores them, collapsed, when the file opens again, so they hide literals that no controller tracks, in any
     * presentation. Recognized by their empty placeholder (a user's own fold shows "...") and by covering exactly
     * one literal of some lens.
     */
    private fun orphanFolds(): List<FoldRegion> {
        val tracked = elements.mapNotNullTo(HashSet()) { it.fold }
        val candidates = editor.foldingModel.allFoldRegions
            .filter { it.isValid && it.placeholderText.isEmpty() && it !in tracked }
        if (candidates.isEmpty()) return emptyList()
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: editor.virtualFile ?: return emptyList()
        val context = LensContext(file.fileType, (file.fileType as? LanguageFileType)?.language, document)
        return candidates.filter { fold ->
            val text = document.getText(fold.textRange)
            host.allLenses.any { it.find(text, context).singleOrNull()?.range == TextRange(0, text.length) }
        }
    }

    private fun removeOrphanFolds() {
        val orphans = orphanFolds()
        if (orphans.isEmpty()) return
        editor.foldingModel.runBatchFoldingOperation({ orphans.forEach(editor.foldingModel::removeFoldRegion) }, false, false)
    }

    /** What one scan found, for the document as of [stamp]. Null [matches] means the editor shows nothing. */
    private class Scan(val stamp: Long, val presentation: LensPresentation, val matches: List<ScannedMatch<*>>?)

    private fun scheduleRefresh(delayMillis: Int = REFRESH_DELAY_MS) {
        alarm.cancelAllRequests()
        alarm.addRequest(::refreshInBackground, delayMillis)
    }

    /** Scans in a background read action and applies the result on the EDT. Newer requests replace pending ones. */
    internal fun refreshInBackground(): CancellablePromise<*> =
        ReadAction.nonBlocking<Scan?> { if (editor.isDisposed) null else scan() }
            .coalesceBy(this)
            .expireWith(this)
            .finishOnUiThread(ModalityState.stateForComponent(editor.component)) { it?.let(::apply) }
            .submit(AppExecutorUtil.getAppExecutorService())

    /** Brings elements in line with the document text right away, on the EDT. */
    internal fun refresh() {
        if (!editor.isDisposed) apply(scan())
    }

    private fun scan(): Scan {
        val document = editor.document
        val stamp = document.modificationStamp
        val presentation = host.presentation
        val context = context()
        if (presentation == LensPresentation.OFF || context == null) return Scan(stamp, presentation, null)
        return Scan(stamp, presentation, LensScanner.scan(document.immutableCharSequence, context, host.lenses))
    }

    /** Keeps the elements whose literal didn't change, and replaces the rest. */
    private fun apply(scan: Scan) {
        if (editor.isDisposed) return
        val document = editor.document
        // The document changed while scanning; the change already scheduled a newer scan.
        if (scan.stamp != document.modificationStamp) return
        removeOrphanFolds()
        try {
            applyMatches(scan)
        } finally {
            clearEdited()
        }
    }

    private fun applyMatches(scan: Scan) {
        val document = editor.document
        val presentation = scan.presentation
        val matches = scan.matches
        if (matches == null) {
            clear()
            return
        }
        val wanted = matches.mapTo(HashSet()) { Triple(it.lens.id, it.range, document.getText(it.range)) }
        val kept = HashSet<Triple<String, TextRange, String>>()
        val stale = elements.filter { element ->
            val alive = element.marker.isValid && element.inlay.isValid && element.fold?.isValid != false &&
                element.key in wanted
            !(alive && kept.add(element.key))
        }
        val added = matches.filter { Triple(it.lens.id, it.range, document.getText(it.range)) !in kept }
        if (stale.isEmpty() && added.isEmpty()) return

        remove(stale)
        val created = added.mapNotNull { createElement(it, presentation) }
        elements += created
        if (presentation == LensPresentation.INLAY_ONLY && created.isNotEmpty()) {
            editor.foldingModel.runBatchFoldingOperation({
                created.forEach { it.fold = addFold(it.marker.textRange) }
            }, false, false)
        }
    }

    private fun clearEdited() {
        edited.forEach(RangeMarker::dispose)
        edited.clear()
    }

    private fun <V : Any> createElement(scanned: ScannedMatch<V>, presentation: LensPresentation): Element<V>? {
        val range = scanned.range
        val renderer = scanned.lens.renderer(scanned.match)
        // A collapsed fold hides inlays at its start offset, whichever side they relate to, so when the literal is
        // folded away the inlay goes at its end, attached to it so that typing right after it doesn't move it.
        val folded = presentation == LensPresentation.INLAY_ONLY
        val offset = if (folded) range.endOffset else range.startOffset
        val inlay = editor.inlayModel.addInlineElement(offset, folded, renderer) ?: return null
        val document = editor.document
        return Element(scanned.lens, scanned.match, document.createRangeMarker(range), document.getText(range), inlay)
    }

    private fun context(): LensContext? {
        val document = editor.document
        if (document.textLength > host.maxDocumentLength) return null
        val file = FileDocumentManager.getInstance().getFile(document) ?: editor.virtualFile ?: return null
        if (host.isExcluded(file.fileType)) return null
        return LensContext(file.fileType, (file.fileType as? LanguageFileType)?.language, document)
    }

    // A literal being typed stays visible until the caret leaves it, so it doesn't fold away mid-typing.
    private fun addFold(range: TextRange): FoldRegion? {
        val fold = editor.foldingModel.addFoldRegion(range.startOffset, range.endOffset, "") ?: return null
        fold.putUserData(LENS_FOLD, true)
        // Only a literal being typed starts unfolded; one the caret merely touches (a file opened with the caret next
        // to it, a mode switch) starts folded, like it would be after the caret stepped over it.
        if (isCaretNear(range) && edited.any { it.isValid && it.textRange.intersects(range) }) show(fold) else fold.isExpanded = false
        return fold
    }

    private fun show(fold: FoldRegion) {
        fold.isExpanded = true
        fold.putUserData(SHOWN, true)
    }

    /**
     * In inlay-only mode, folds a literal again once no caret touches it. Never unfolds one: the caret steps over a
     * folded literal like over one character, and its text shows only while it's typed or edited, or if the user
     * expands the fold (Expand, ⌘+).
     */
    private fun updateFoldExpansion() {
        val changed = foldsToCollapse()
        if (changed.isEmpty()) return
        editor.foldingModel.runBatchFoldingOperation({
            changed.forEach {
                it.isExpanded = false
                it.putUserData(SHOWN, null)
            }
        }, false, false)
        // A caret left inside a folded literal would unfold it again, so move it out: past the literal if it came from
        // its start on the same line (IdeaVim's l), else to its start, next to the swatch (h, or j/k from another line).
        val document = editor.document
        for (caret in editor.caretModel.allCarets) {
            val fold = changed.firstOrNull { caret.offset > it.startOffset && caret.offset < it.endOffset } ?: continue
            val from = caret.getUserData(OFFSET_BEFORE_FOLD)
            val forward = from != null && from <= fold.startOffset && from <= document.textLength &&
                document.getLineNumber(from) == document.getLineNumber(fold.startOffset)
            caret.moveToOffset(if (forward) fold.endOffset else fold.startOffset)
        }
    }

    /**
     * Expanded folds that should be collapsed. One the engine or the user expanded stays expanded while a caret
     * touches it. Anything else that expands folds, such as navigation unfolding the caret's line or IdeaVim
     * unfolding a literal its caret moves into, only shows a literal while a selection covers part of it.
     */
    private fun foldsToCollapse() = elements.mapNotNull { it.fold }.filter { fold ->
        fold.isValid && fold.isExpanded && !(if (fold.getUserData(SHOWN) == true) isCaretNear(fold.textRange) else isSelected(fold.textRange))
    }

    private fun isSelected(range: TextRange) = editor.caretModel.allCarets.any {
        it.hasSelection() && TextRange(it.selectionStart, it.selectionEnd).intersectsStrict(range)
    }

    private fun isCaretNear(range: TextRange) =
        editor.caretModel.allCarets.any { it.offset in range.startOffset..range.endOffset }

    private fun clear() {
        if (!editor.isDisposed) remove(elements.toList())
    }

    // Inlays are disposed outside the batch folding operation: the editor relayouts on inlay removal
    // and must not see the folds that are being removed.
    private fun remove(toRemove: List<Element<*>>) {
        if (toRemove.isEmpty()) return
        val folds = toRemove.mapNotNull { it.fold?.takeIf(FoldRegion::isValid) }
        if (folds.isNotEmpty()) {
            editor.foldingModel.runBatchFoldingOperation({ folds.forEach(editor.foldingModel::removeFoldRegion) }, false, false)
        }
        for (element in toRemove) {
            element.inlay.dispose()
            element.marker.dispose()
        }
        elements.removeAll(toRemove.toSet())
    }

    private fun elementAt(event: EditorMouseEvent): Element<*>? {
        val inlay = event.inlay ?: return null
        return elements.find { it.inlay == inlay }
    }

    private val Element<*>.isEditable: Boolean get() = editorOf(this) != null

    private fun <V : Any> editorOf(element: Element<V>): LensEditor<V>? = element.lens.editor(element.match)

    private fun <V : Any> startEdit(element: Element<V>, location: RelativePoint) {
        val lensEditor = editorOf(element) ?: return
        val project = editor.project
        if (!FileDocumentManager.getInstance().requestWriting(editor.document, project)) return
        val session = EditSession(element.lens, lensEditor, element.match, element.marker.textRange)
        lensEditor.show(project, element.match.value, location, session::apply, session::dispose)
    }

    /**
     * Writes edited values into the document. Every change of one edit session shares a command group,
     * so a single undo reverts the whole edit.
     */
    internal inner class EditSession<V : Any>(
        private val lens: Lens<V>,
        private val lensEditor: LensEditor<V>,
        private val original: LensMatch<V>,
        range: TextRange,
    ) {
        private val document = editor.document
        private val groupId = "chromabox.edit." + UUID.randomUUID()

        // Greedy, so the marker keeps covering the literal after it's replaced with a longer or shorter one.
        private val marker = document.createRangeMarker(range).apply {
            isGreedyToLeft = true
            isGreedyToRight = true
        }

        fun apply(value: V) {
            if (!marker.isValid || editor.isDisposed) return
            val range = marker.textRange
            val current = document.getText(range)
            if (!isWholeLiteral(current)) return
            val replacement = lensEditor.format(value, original) ?: return
            if (replacement == current) return
            WriteCommandAction.writeCommandAction(editor.project)
                .withName(lensEditor.commandName)
                .withGroupId(groupId)
                .run<RuntimeException> { document.replaceString(range.startOffset, range.endOffset, replacement) }
        }

        /** True if [text] still is one literal of this lens, so a hand edit in the meantime isn't overwritten. */
        private fun isWholeLiteral(text: String): Boolean {
            val context = context() ?: return false
            return lens.find(text, context).singleOrNull()?.range == TextRange(0, text.length)
        }

        fun dispose() = marker.dispose()
    }

    // Test hooks.
    internal val matchRanges: List<TextRange> get() = elements.map { it.marker.textRange }.sortedBy { it.startOffset }
    internal val foldedRanges: List<TextRange> get() = elements.mapNotNull { it.fold?.takeIf { f -> !f.isExpanded }?.textRange }.sortedBy { it.startOffset }

    @Suppress("UNCHECKED_CAST")
    internal fun editSessionAt(offset: Int): EditSession<Any>? {
        val element = elements.find { offset in it.marker.textRange.startOffset until it.marker.textRange.endOffset }
            as Element<Any>? ?: return null
        val lensEditor = editorOf(element) ?: return null
        return EditSession(element.lens, lensEditor, element.match, element.marker.textRange)
    }

    override fun dispose() {
        clearEdited()
        if (!editor.isDisposed) {
            clear()
            (editor as? EditorEx)?.setCustomCursor(this, null)
        }
        editor.putUserData(CONTROLLER, null)
    }

    companion object {
        private const val REFRESH_DELAY_MS = 200
        private val CONTROLLER = Key.create<EditorLensController>("chromabox.lens.controller")

        /** Marks fold regions created by the engine, so they're never mistaken for language folds. */
        val LENS_FOLD = Key.create<Boolean>("chromabox.lens.fold")

        /** Marks lens folds that the engine or the user expanded; see [foldsToCollapse]. */
        private val SHOWN = Key.create<Boolean>("chromabox.lens.fold.shown")

        /** Where a caret was before it last moved, unless that was inside a lens fold. */
        private val OFFSET_BEFORE_FOLD = Key.create<Int>("chromabox.caret.offset.before.fold")

        fun getOrCreate(editor: Editor, host: LensHost): EditorLensController {
            editor.getUserData(CONTROLLER)?.let { return it }
            val controller = EditorLensController(editor, host)
            editor.putUserData(CONTROLLER, controller)
            // Parented by the plugin, not the editor, so unloading the plugin removes it from open editors.
            Disposer.register(host.parentDisposable, controller)
            controller.scheduleRefresh(0)
            return controller
        }

        fun release(editor: Editor) {
            editor.getUserData(CONTROLLER)?.let(Disposer::dispose)
        }
    }
}
