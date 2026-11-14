package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.engine.EditorLensController
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color

class EditorLensControllerTest : BasePlatformTestCase() {
    private val settings get() = ChromaboxSettings.getInstance()
    private var savedMode = DisplayMode.BOX_WITH_HEX

    override fun setUp() {
        super.setUp()
        savedMode = settings.mode
    }

    override fun tearDown() {
        try {
            settings.mode = savedMode
        } finally {
            super.tearDown()
        }
    }

    private fun controllerFor(fileName: String, text: String, mode: DisplayMode): EditorLensController {
        settings.mode = mode
        myFixture.configureByText(fileName, text)
        return EditorLensController.getOrCreate(myFixture.editor, ChromaboxHost).also { it.refresh() }
    }

    fun `test markdown gets a swatch per color`() {
        val controller = controllerFor("a.md", "# Title\nPrimary `#1E90FF`, accent #f00.", DisplayMode.BOX_WITH_HEX)
        assertEquals(listOf(TextRange(17, 24), TextRange(34, 38)), controller.matchRanges)
        assertEquals(2, myFixture.editor.inlayModel.getInlineElementsInRange(0, 40, ColorSwatchRenderer::class.java).size)
        assertEmpty(controller.foldedRanges)
    }

    fun `test named colors and float tuples get swatches only once enabled`() {
        val text = "tomato and [1.0, 0.5, 0.0]"
        val controller = controllerFor("a.md", text, DisplayMode.BOX_WITH_HEX)
        assertEmpty(controller.matchRanges)
        val formats = listOf(ColorFormat.NAMED, ColorFormat.FLOAT_TUPLES)
        try {
            formats.forEach { settings.setLensEnabled(it.lensId, true) }
            settings.notifyChanged()
            controller.refresh()
            assertEquals(listOf(TextRange(0, 6), TextRange(11, 26)), controller.matchRanges)
        } finally {
            formats.forEach { settings.setLensEnabled(it.lensId, false) }
            settings.notifyChanged()
        }
    }

    fun `test csv works too`() {
        val controller = controllerFor("a.csv", "name,color\nred,#ff0000\ngreen,#00ff00\n", DisplayMode.BOX_WITH_HEX)
        assertEquals(2, controller.matchRanges.size)
    }

    fun `test css is skipped`() {
        val controller = controllerFor("a.css", "a { color: #ff0000; }", DisplayMode.BOX_WITH_HEX)
        assertEmpty(controller.matchRanges)
    }

    fun `test box only folds hex away from the caret`() {
        val controller = controllerFor("a.md", "<caret>x #ff0000 and #00ff00", DisplayMode.BOX_ONLY)
        assertEquals(listOf(TextRange(2, 9), TextRange(14, 21)), controller.foldedRanges)
    }

    fun `test box only swatches stay visible next to folded hex`() {
        controllerFor("a.md", "<caret>x\n| a | #d8d8da |\n#ae5519 y\n", DisplayMode.BOX_ONLY)
        val inlays = myFixture.editor.inlayModel.getInlineElementsInRange(0, myFixture.editor.document.textLength)
        assertEquals(2, inlays.size)
        for (inlay in inlays) assertNotNull("inlay at ${inlay.offset} is hidden", inlay.bounds)
    }

    fun `test box only caret steps over a folded color without unfolding it`() {
        val controller = controllerFor("a.md", "<caret>x #ff0000 y", DisplayMode.BOX_ONLY)
        repeat(3) { myFixture.performEditorAction(IdeActions.ACTION_EDITOR_MOVE_CARET_RIGHT) }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(9, myFixture.editor.caretModel.offset)
        assertEquals(listOf(TextRange(2, 9)), controller.foldedRanges)
    }

    fun `test box only folds a typed color once the caret leaves it`() {
        val controller = controllerFor("a.md", "x <caret>", DisplayMode.BOX_ONLY)
        myFixture.type("#00f")
        controller.refresh()
        assertEmpty(controller.foldedRanges)
        myFixture.type(" and more")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(TextRange(2, 6)), controller.foldedRanges)
    }

    fun `test box only shows a folded color that an edit changes`() {
        val controller = controllerFor("a.md", "<caret>x #ff0000 y", DisplayMode.BOX_ONLY)
        myFixture.editor.caretModel.moveToOffset(9)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(TextRange(2, 9)), controller.foldedRanges)

        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("x #ff000 y", myFixture.editor.document.text)
        assertEmpty(controller.foldedRanges)
    }

    fun `test box only typing right after a folded color keeps it folded`() {
        val controller = controllerFor("a.md", "<caret>x #ff0000", DisplayMode.BOX_ONLY)
        myFixture.editor.caretModel.moveToOffset(9)
        myFixture.type(" y")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        controller.refresh()
        assertEquals("x #ff0000 y", myFixture.editor.document.text)
        assertEquals(listOf(TextRange(2, 9)), controller.foldedRanges)
    }

    /** Adds collapsed folds like the ones the IDE restores from an earlier session: untracked, empty placeholder. */
    private fun addRestoredFold(start: Int, end: Int, placeholder: String = "") {
        val folding = myFixture.editor.foldingModel
        folding.runBatchFoldingOperation { folding.addFoldRegion(start, end, placeholder)!!.isExpanded = false }
    }

    private fun foldsOf(range: TextRange) = myFixture.editor.foldingModel.allFoldRegions.filter { it.textRange == range }

    fun `test folds restored from an earlier session are removed in every mode`() {
        for (mode in DisplayMode.entries) {
            settings.mode = mode
            myFixture.configureByText("${mode.name}.md", "<caret>x #ff0000 and #00ff00")
            EditorLensController.release(myFixture.editor)
            addRestoredFold(2, 9)
            addRestoredFold(14, 21, "...")
            EditorLensController.getOrCreate(myFixture.editor, ChromaboxHost).refresh()

            val restored = foldsOf(TextRange(2, 9))
            if (mode == DisplayMode.BOX_ONLY) {
                assertEquals(mode.name, 1, restored.size)
                assertEquals(mode.name, true, restored.single().getUserData(EditorLensController.LENS_FOLD))
            } else {
                assertEmpty(mode.name, restored)
            }
            // A fold the user made over a color, with a visible placeholder, stays.
            assertEquals(mode.name, "...", foldsOf(TextRange(14, 21)).single().placeholderText)
            if (mode == DisplayMode.BOX_WITH_HEX) {
                val inlay = myFixture.editor.inlayModel.getInlineElementsInRange(2, 2).single()
                assertNotNull("swatch hidden", inlay.bounds)
            }
            EditorLensController.release(myFixture.editor)
        }
    }

    fun `test folds restored after the first scan are removed too`() {
        controllerFor("a.md", "<caret>x #ff0000 y", DisplayMode.BOX_WITH_HEX)
        addRestoredFold(2, 9)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEmpty(foldsOf(TextRange(2, 9)))
    }

    fun `test box only folds hex next to the caret when the file opens`() {
        val controller = controllerFor("a.md", "x #ff0000<caret> and #00ff00", DisplayMode.BOX_ONLY)
        assertEquals(listOf(TextRange(2, 9), TextRange(14, 21)), controller.foldedRanges)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_MOVE_CARET_LEFT)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(TextRange(2, 9), TextRange(14, 21)), controller.foldedRanges)
    }

    fun `test box only folds back a color that navigation unfolds next to the caret`() {
        val controller = controllerFor("a.md", "| a | #c8c8c8 |\n| b | #85664d |\n", DisplayMode.BOX_ONLY)
        for (offset in listOf(22, 29)) {
            OpenFileDescriptor(project, myFixture.file.virtualFile, offset).navigateIn(myFixture.editor)
            // Folded back before anything else runs, so the editor never paints the hex.
            assertEquals("caret at $offset", listOf(TextRange(6, 13), TextRange(22, 29)), controller.foldedRanges)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEquals("caret at $offset", listOf(TextRange(6, 13), TextRange(22, 29)), controller.foldedRanges)
        }
    }

    fun `test box only folds back a color another plugin unfolds to put the caret inside`() {
        val controller = controllerFor("a.md", "| a | #c8c8c8 |\n| b | #85664d |\n", DisplayMode.BOX_ONLY)
        // What IdeaVim does when j/k lands inside a fold: unfold it, then move the caret in.
        val editor = myFixture.editor
        val fold = editor.foldingModel.allFoldRegions.first { it.startOffset == 22 }
        editor.foldingModel.runBatchFoldingOperation { fold.isExpanded = true }
        assertEquals(listOf(TextRange(6, 13), TextRange(22, 29)), controller.foldedRanges)
        editor.caretModel.moveToOffset(23)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(TextRange(6, 13), TextRange(22, 29)), controller.foldedRanges)
        assertEquals(22, editor.caretModel.offset)
    }

    fun `test box only moves a caret that another plugin puts inside a color in the direction it went`() {
        val controller = controllerFor("a.md", "x #ff0000 y", DisplayMode.BOX_ONLY)
        val editor = myFixture.editor
        val fold = editor.foldingModel.allFoldRegions.single()
        // IdeaVim's l onto the first character of the color or from its start, then h from its end.
        for ((move, expected) in listOf(Pair(1, 2) to 9, Pair(2, 3) to 9, Pair(9, 8) to 2)) {
            editor.caretModel.moveToOffset(move.first)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            editor.foldingModel.runBatchFoldingOperation { fold.isExpanded = true }
            editor.caretModel.moveToOffset(move.second)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEquals("$move", expected, editor.caretModel.offset)
            // Past the color the caret is drawn after its swatch, before it the caret is drawn before it.
            assertEquals("$move", editor.offsetToVisualPosition(expected, expected == 9, false), editor.caretModel.visualPosition)
            assertEquals(listOf(TextRange(2, 9)), controller.foldedRanges)
        }
    }

    fun `test box only keeps a selected color unfolded`() {
        val controller = controllerFor("a.md", "x #ff0000 y", DisplayMode.BOX_ONLY)
        val editor = myFixture.editor
        val fold = editor.foldingModel.allFoldRegions.single()
        editor.foldingModel.runBatchFoldingOperation { fold.isExpanded = true }
        editor.selectionModel.setSelection(3, 9)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEmpty(controller.foldedRanges)
    }

    fun `test box only keeps a color the user expands`() {
        val controller = controllerFor("a.md", "x #ff0000<caret> y", DisplayMode.BOX_ONLY)
        myFixture.performEditorAction(IdeActions.ACTION_EXPAND_REGION)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEmpty(controller.foldedRanges)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_MOVE_LINE_END)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(TextRange(2, 9)), controller.foldedRanges)
    }

    fun `test switching to box only folds hex next to the caret`() {
        val controller = controllerFor("a.md", "x #ff0000<caret> y", DisplayMode.BOX_WITH_HEX)
        settings.mode = DisplayMode.BOX_ONLY
        controller.refresh()
        assertEquals(listOf(TextRange(2, 9)), controller.foldedRanges)
    }

    fun `test gutter mode shows one icon per line and no inlays or folds`() {
        val text = "#ff0000\nnothing\n#00ff00 and rgb(0, 0, 255)\n#111 #222 #333 #444 #555\n"
        val controller = controllerFor("a.md", text, DisplayMode.GUTTER)
        assertEquals(8, controller.matchRanges.size)
        assertEmpty(myFixture.editor.inlayModel.getInlineElementsInRange(0, text.length))
        assertEmpty(myFixture.editor.foldingModel.allFoldRegions.toList())
        val icons = controller.lineIconRenderers
        assertEquals(listOf(1, 2, 4), icons.map { it.segmentCount })
        assertEquals(listOf(false, false, true), icons.map { it.hasMore })
        assertEquals("<html>#00ff00<br>rgb(0, 0, 255)</html>", icons[1].tooltipText)
    }

    fun `test gutter mode replaces only the icons of lines that change`() {
        val controller = controllerFor("a.md", "#ff0000\n#00ff00\n", DisplayMode.GUTTER)
        val (first, second) = controller.lineIconRenderers
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.replaceString(9, 10, "1") }
        controller.refresh()
        val icons = controller.lineIconRenderers
        assertSame(first, icons[0])
        assertNotSame(second, icons[1])
    }

    fun `test gutter mode merges icons of lines an edit joins`() {
        val controller = controllerFor("a.md", "#ff0000\n#00ff00", DisplayMode.GUTTER)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.replaceString(7, 8, " ") }
        controller.refresh()
        assertEquals(listOf(2), controller.lineIconRenderers.map { it.segmentCount })
    }

    fun `test leaving gutter mode removes the icons`() {
        val controller = controllerFor("a.md", "#ff0000", DisplayMode.GUTTER)
        assertEquals(1, controller.lineIconRenderers.size)
        settings.mode = DisplayMode.BOX_WITH_HEX
        controller.refresh()
        assertEmpty(controller.lineIconRenderers)
        assertEquals(1, myFixture.editor.inlayModel.getInlineElementsInRange(0, 7).size)
        assertEmpty(myFixture.editor.markupModel.allHighlighters.filter { it.gutterIconRenderer != null })
    }

    fun `test cycling goes through all four modes`() {
        settings.mode = DisplayMode.BOX_ONLY
        val seen = List(4) {
            CycleDisplayModeAction().actionPerformed(TestActionEvent.createTestEvent())
            settings.mode
        }
        assertEquals(listOf(DisplayMode.BOX_WITH_HEX, DisplayMode.GUTTER, DisplayMode.OFF, DisplayMode.BOX_ONLY), seen)
    }

    fun `test off removes swatches`() {
        val controller = controllerFor("a.md", "#ff0000", DisplayMode.BOX_WITH_HEX)
        assertEquals(1, controller.matchRanges.size)
        settings.mode = DisplayMode.OFF
        assertEmpty(controller.matchRanges)
        assertEmpty(myFixture.editor.inlayModel.getInlineElementsInRange(0, 7))
    }

    fun `test plugin unload removes swatches and folds from open editors`() {
        val controller = controllerFor("a.md", "<caret>x #ff0000", DisplayMode.BOX_ONLY)
        assertEquals(1, controller.foldedRanges.size)
        ChromaboxLifetime.getInstance().disposeControllers()
        assertEmpty(myFixture.editor.inlayModel.getInlineElementsInRange(0, 9))
        assertEmpty(myFixture.editor.foldingModel.allFoldRegions.filter { it.getUserData(EditorLensController.LENS_FOLD) == true })
        assertNotSame(controller, EditorLensController.getOrCreate(myFixture.editor, ChromaboxHost))
    }

    fun `test editing text updates swatches`() {
        val controller = controllerFor("a.md", "<caret> #ff0000", DisplayMode.BOX_WITH_HEX)
        myFixture.type("#00f ")
        controller.refresh()
        assertEquals(listOf(TextRange(0, 4), TextRange(6, 13)), controller.matchRanges)
    }

    fun `test css functions get swatches and keep their notation when picked`() {
        val controller = controllerFor("a.md", "fg rgb(255, 0, 0) bg #fff", DisplayMode.BOX_WITH_HEX)
        assertEquals(listOf(TextRange(3, 17), TextRange(21, 25)), controller.matchRanges)

        val session = controller.editSessionAt(3)!!
        session.apply(ChromaColor.fromAwt(Color(0, 255, 0, 0x80)))
        session.dispose()
        assertEquals("fg rgba(0, 255, 0, 0.5) bg #fff", myFixture.editor.document.text)

        val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(myFixture.file.virtualFile) as TextEditor
        UndoManager.getInstance(project).undo(fileEditor)
        assertEquals("fg rgb(255, 0, 0) bg #fff", myFixture.editor.document.text)
    }

    fun `test background refresh applies matches on the EDT`() {
        val controller = controllerFor("a.md", "<caret> #ff0000", DisplayMode.BOX_WITH_HEX)
        myFixture.type("#00f ")
        PlatformTestUtil.waitForPromise(controller.refreshInBackground())
        assertEquals(listOf(TextRange(0, 4), TextRange(6, 13)), controller.matchRanges)
    }

    fun `test picking colors rewrites hex and undoes in one step`() {
        val controller = controllerFor("a.md", "color: #F00;", DisplayMode.BOX_WITH_HEX)
        val session = controller.editSessionAt(8)!!
        session.apply(ChromaColor.fromAwt(Color(0x11, 0x22, 0x33)))
        assertEquals("color: #123;", myFixture.editor.document.text)
        session.apply(ChromaColor.fromAwt(Color(0x1E, 0x90, 0xFF)))
        assertEquals("color: #1E90FF;", myFixture.editor.document.text)
        session.apply(ChromaColor.fromAwt(Color(0x1E, 0x90, 0xFF, 0x80)))
        assertEquals("color: #1E90FF80;", myFixture.editor.document.text)
        session.dispose()

        val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(myFixture.file.virtualFile) as TextEditor
        UndoManager.getInstance(project).undo(fileEditor)
        assertEquals("color: #F00;", myFixture.editor.document.text)
    }
}
