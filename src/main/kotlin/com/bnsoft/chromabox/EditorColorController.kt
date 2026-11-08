package com.bnsoft.chromabox

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.ui.ColorChooserService
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.picker.ColorListener
import com.intellij.util.Alarm
import java.awt.Color
import java.awt.Cursor
import java.util.UUID
import javax.swing.SwingUtilities

/**
 * Shows a [ColorSwatchRenderer] inlay before every hex color in one editor and, in [DisplayMode.BOX_ONLY],
 * folds the hex text away. Clicking a swatch opens the color picker and writes the picked color back.
 */
class EditorColorController private constructor(private val editor: Editor) : Disposable {
    private class Swatch(
        val marker: RangeMarker,
        val hex: String,
        val format: HexFormat,
        val inlay: Inlay<ColorSwatchRenderer>,
        var fold: FoldRegion? = null,
    )

    private val swatches = mutableListOf<Swatch>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val settings get() = ChromaboxSettings.getInstance()

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = scheduleRefresh()
        }, this)
        editor.caretModel.addCaretListener(object : CaretListener {
            // Folding can't be changed from inside a caret event, so defer it.
            override fun caretPositionChanged(event: CaretEvent) =
                ApplicationManager.getApplication().invokeLater({ updateFoldExpansion() }, { editor.isDisposed })
        }, this)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mousePressed(event: EditorMouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event.mouseEvent)) return
                val swatch = swatchAt(event) ?: return
                // Keep the caret where it is, so a folded hex doesn't unfold.
                event.consume()
                showPicker(swatch, RelativePoint(event.mouseEvent))
            }
        }, this)
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) {
                val cursor = if (swatchAt(event) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
                (editor as? EditorEx)?.setCustomCursor(this@EditorColorController, cursor)
            }
        }, this)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(ChromaboxSettingsListener.TOPIC, ChromaboxSettingsListener {
                clear()
                refresh()
            })
    }

    private fun scheduleRefresh(delayMillis: Int = REFRESH_DELAY_MS) {
        alarm.cancelAllRequests()
        alarm.addRequest(::refresh, delayMillis)
    }

    /** Brings swatches in line with the document text, keeping the ones whose hex didn't change. */
    internal fun refresh() {
        if (editor.isDisposed) return
        val mode = settings.mode
        if (!isEnabled(mode)) {
            clear()
            return
        }
        val document = editor.document
        val matches = HexColorParser.findAll(document.immutableCharSequence, settings.state.shortForms)
        val wanted = matches.mapTo(HashSet()) { it.range }
        val kept = HashSet<TextRange>()
        val stale = swatches.filter { swatch ->
            val range = swatch.marker.textRange
            val alive = swatch.marker.isValid && swatch.inlay.isValid && swatch.fold?.isValid != false &&
                range in wanted && document.getText(range) == swatch.hex
            !(alive && kept.add(range))
        }
        val added = matches.filter { it.range !in kept }
        if (stale.isEmpty() && added.isEmpty()) return

        remove(stale)
        val created = added.mapNotNull { match ->
            val inlay = editor.inlayModel.addInlineElement(match.range.startOffset, false, ColorSwatchRenderer(match.color))
                ?: return@mapNotNull null
            Swatch(document.createRangeMarker(match.range), document.getText(match.range), match.format, inlay)
        }
        swatches += created
        if (mode == DisplayMode.BOX_ONLY && created.isNotEmpty()) {
            editor.foldingModel.runBatchFoldingOperation({
                created.forEach { it.fold = addFold(it.marker.textRange) }
            }, false, false)
        }
    }

    private fun addFold(range: TextRange): FoldRegion? {
        val fold = editor.foldingModel.addFoldRegion(range.startOffset, range.endOffset, "") ?: return null
        fold.putUserData(CHROMABOX_FOLD, true)
        fold.isExpanded = isCaretNear(range)
        return fold
    }

    /** In box-only mode, shows the hex text while a caret touches it so it can still be edited by hand. */
    private fun updateFoldExpansion() {
        val changed = swatches.mapNotNull { it.fold }
            .filter { it.isValid && it.isExpanded != isCaretNear(it.textRange) }
        if (changed.isEmpty()) return
        editor.foldingModel.runBatchFoldingOperation({
            changed.forEach { it.isExpanded = !it.isExpanded }
        }, false, false)
    }

    private fun isCaretNear(range: TextRange) =
        editor.caretModel.allCarets.any { it.offset in range.startOffset..range.endOffset }

    private fun isEnabled(mode: DisplayMode): Boolean {
        if (mode == DisplayMode.OFF) return false
        if (editor.document.textLength > ChromaboxSettings.MAX_DOCUMENT_LENGTH) return false
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: editor.virtualFile ?: return false
        return !settings.isExcluded(file.fileType)
    }

    private fun clear() {
        if (!editor.isDisposed) remove(swatches.toList())
    }

    // Inlays are disposed outside the batch folding operation: the editor relayouts on inlay removal
    // and must not see the folds that are being removed.
    private fun remove(toRemove: List<Swatch>) {
        if (toRemove.isEmpty()) return
        val folds = toRemove.mapNotNull { it.fold?.takeIf(FoldRegion::isValid) }
        if (folds.isNotEmpty()) {
            editor.foldingModel.runBatchFoldingOperation({ folds.forEach(editor.foldingModel::removeFoldRegion) }, false, false)
        }
        for (swatch in toRemove) {
            swatch.inlay.dispose()
            swatch.marker.dispose()
        }
        swatches.removeAll(toRemove.toSet())
    }

    private fun swatchAt(event: EditorMouseEvent): Swatch? {
        val inlay = event.inlay ?: return null
        return swatches.find { it.inlay == inlay }
    }

    private fun showPicker(swatch: Swatch, location: RelativePoint) {
        val project = editor.project
        if (!FileDocumentManager.getInstance().requestWriting(editor.document, project)) return
        val session = PickerSession(swatch.marker.textRange, swatch.format)
        ColorChooserService.getInstance().showPopup(
            project, swatch.inlay.renderer.color, ColorListener { color, _ -> session.apply(color) }, location,
            showAlpha = true, popupCloseListener = { session.dispose() },
        )
    }

    /**
     * Writes picked colors into the document. Every change of one picker popup shares a command group,
     * so a single undo reverts the whole pick.
     */
    internal inner class PickerSession(range: TextRange, private val format: HexFormat) {
        private val document = editor.document
        private val groupId = "chromabox.pick." + UUID.randomUUID()

        // Greedy, so the marker keeps covering the hex after it's replaced with a longer or shorter one.
        private val marker = document.createRangeMarker(range).apply {
            isGreedyToLeft = true
            isGreedyToRight = true
        }

        fun apply(color: Color) {
            if (!marker.isValid || editor.isDisposed) return
            val range = marker.textRange
            val current = document.getText(range)
            if (HexColorParser.parse(current) == null) return
            val replacement = HexColorParser.format(color, format)
            if (replacement == current) return
            WriteCommandAction.writeCommandAction(editor.project)
                .withName(ChromaboxBundle.message("command.changeColor"))
                .withGroupId(groupId)
                .run<RuntimeException> { document.replaceString(range.startOffset, range.endOffset, replacement) }
        }

        fun dispose() = marker.dispose()
    }

    // Test hooks.
    internal val swatchRanges: List<TextRange> get() = swatches.map { it.marker.textRange }.sortedBy { it.startOffset }
    internal val foldedRanges: List<TextRange> get() = swatches.mapNotNull { it.fold?.takeIf { f -> !f.isExpanded }?.textRange }.sortedBy { it.startOffset }

    internal fun pickerSessionAt(offset: Int): PickerSession? =
        swatches.find { offset in it.marker.textRange.startOffset until it.marker.textRange.endOffset }
            ?.let { PickerSession(it.marker.textRange, it.format) }

    override fun dispose() {
        if (!editor.isDisposed) {
            clear()
            (editor as? EditorEx)?.setCustomCursor(this, null)
        }
        editor.putUserData(CONTROLLER, null)
    }

    companion object {
        private const val REFRESH_DELAY_MS = 200
        private val CONTROLLER = Key.create<EditorColorController>("chromabox.controller")

        /** Marks fold regions created by Chromabox, so they're never mistaken for language folds. */
        val CHROMABOX_FOLD = Key.create<Boolean>("chromabox.fold")

        fun getOrCreate(editor: Editor): EditorColorController {
            editor.getUserData(CONTROLLER)?.let { return it }
            val controller = EditorColorController(editor)
            editor.putUserData(CONTROLLER, controller)
            // Parented by the plugin, not the editor, so unloading the plugin removes it from open editors.
            Disposer.register(ChromaboxLifetime.getInstance(), controller)
            controller.scheduleRefresh(0)
            return controller
        }

        fun release(editor: Editor) {
            editor.getUserData(CONTROLLER)?.let(Disposer::dispose)
        }
    }
}
