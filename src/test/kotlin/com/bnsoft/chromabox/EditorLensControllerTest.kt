package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.engine.EditorLensController
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
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

    fun `test box only keeps hex next to the caret unfolded`() {
        val controller = controllerFor("a.md", "x <caret>#ff0000 and #00ff00", DisplayMode.BOX_ONLY)
        assertEquals(listOf(TextRange(14, 21)), controller.foldedRanges)
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
