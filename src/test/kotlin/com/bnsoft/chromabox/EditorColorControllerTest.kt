package com.bnsoft.chromabox

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color

class EditorColorControllerTest : BasePlatformTestCase() {
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

    private fun controllerFor(fileName: String, text: String, mode: DisplayMode): EditorColorController {
        settings.mode = mode
        myFixture.configureByText(fileName, text)
        return EditorColorController.getOrCreate(myFixture.editor).also { it.refresh() }
    }

    fun `test markdown gets a swatch per color`() {
        val controller = controllerFor("a.md", "# Title\nPrimary `#1E90FF`, accent #f00.", DisplayMode.BOX_WITH_HEX)
        assertEquals(listOf(TextRange(17, 24), TextRange(34, 38)), controller.swatchRanges)
        assertEquals(2, myFixture.editor.inlayModel.getInlineElementsInRange(0, 40, ColorSwatchRenderer::class.java).size)
        assertEmpty(controller.foldedRanges)
    }

    fun `test csv works too`() {
        val controller = controllerFor("a.csv", "name,color\nred,#ff0000\ngreen,#00ff00\n", DisplayMode.BOX_WITH_HEX)
        assertEquals(2, controller.swatchRanges.size)
    }

    fun `test css is skipped`() {
        val controller = controllerFor("a.css", "a { color: #ff0000; }", DisplayMode.BOX_WITH_HEX)
        assertEmpty(controller.swatchRanges)
    }

    fun `test box only folds hex away from the caret`() {
        val controller = controllerFor("a.md", "<caret>x #ff0000 and #00ff00", DisplayMode.BOX_ONLY)
        assertEquals(listOf(TextRange(2, 9), TextRange(14, 21)), controller.foldedRanges)
    }

    fun `test box only keeps hex next to the caret unfolded`() {
        val controller = controllerFor("a.md", "x <caret>#ff0000 and #00ff00", DisplayMode.BOX_ONLY)
        assertEquals(listOf(TextRange(14, 21)), controller.foldedRanges)
    }

    fun `test off removes swatches`() {
        val controller = controllerFor("a.md", "#ff0000", DisplayMode.BOX_WITH_HEX)
        assertEquals(1, controller.swatchRanges.size)
        settings.mode = DisplayMode.OFF
        assertEmpty(controller.swatchRanges)
        assertEmpty(myFixture.editor.inlayModel.getInlineElementsInRange(0, 7))
    }

    fun `test plugin unload removes swatches and folds from open editors`() {
        val controller = controllerFor("a.md", "<caret>x #ff0000", DisplayMode.BOX_ONLY)
        assertEquals(1, controller.foldedRanges.size)
        ChromaboxLifetime.getInstance().disposeControllers()
        assertEmpty(myFixture.editor.inlayModel.getInlineElementsInRange(0, 9))
        assertEmpty(myFixture.editor.foldingModel.allFoldRegions.filter { it.getUserData(EditorColorController.CHROMABOX_FOLD) == true })
        assertNotSame(controller, EditorColorController.getOrCreate(myFixture.editor))
    }

    fun `test editing text updates swatches`() {
        val controller = controllerFor("a.md", "<caret> #ff0000", DisplayMode.BOX_WITH_HEX)
        myFixture.type("#00f ")
        controller.refresh()
        assertEquals(listOf(TextRange(0, 4), TextRange(6, 13)), controller.swatchRanges)
    }

    fun `test picking colors rewrites hex and undoes in one step`() {
        val controller = controllerFor("a.md", "color: #F00;", DisplayMode.BOX_WITH_HEX)
        val session = controller.pickerSessionAt(8)!!
        session.apply(Color(0x11, 0x22, 0x33))
        assertEquals("color: #123;", myFixture.editor.document.text)
        session.apply(Color(0x1E, 0x90, 0xFF))
        assertEquals("color: #1E90FF;", myFixture.editor.document.text)
        session.apply(Color(0x1E, 0x90, 0xFF, 0x80))
        assertEquals("color: #1E90FF80;", myFixture.editor.document.text)
        session.dispose()

        val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(myFixture.file.virtualFile) as TextEditor
        UndoManager.getInstance(project).undo(fileEditor)
        assertEquals("color: #F00;", myFixture.editor.document.text)
    }
}
