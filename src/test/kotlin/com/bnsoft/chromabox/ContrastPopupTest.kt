package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.engine.EditorLensController
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.UIUtil
import java.awt.Point

class ContrastPopupTest : BasePlatformTestCase() {
    private val settings get() = ChromaboxSettings.getInstance()
    private var savedMode = DisplayMode.BOX_WITH_HEX

    override fun setUp() {
        super.setUp()
        savedMode = settings.mode
        settings.mode = DisplayMode.BOX_WITH_HEX
    }

    override fun tearDown() {
        try {
            settings.mode = savedMode
        } finally {
            super.tearDown()
        }
    }

    private fun controllerFor(text: String): EditorLensController {
        myFixture.configureByText("a.md", text)
        return EditorLensController.getOrCreate(myFixture.editor, ChromaboxHost).also { it.refresh() }
    }

    private fun pairAt(controller: EditorLensController): ContrastPair {
        val (a, b) = controller.shownMatches.map { ColorAt(it.range, it.value as ChromaColor) }
        return ContrastPairs.of(a, b)
    }

    fun `test shown matches and writing a value as one undo step`() {
        val controller = controllerFor("#777777 on #ffffff")
        assertEquals(listOf(TextRange(0, 7), TextRange(11, 18)), controller.shownMatches.map { it.range })
        assertTrue(controller.write(TextRange(0, 7), ChromaColor.fromRgb255(0x76, 0x76, 0x76)))
        assertEquals("#767676 on #ffffff", myFixture.editor.document.text)
        assertFalse(controller.write(TextRange(1, 7), ChromaColor.fromRgb255(0, 0, 0)))

        val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(myFixture.file.virtualFile) as TextEditor
        UndoManager.getInstance(project).undo(fileEditor)
        assertEquals("#777777 on #ffffff", myFixture.editor.document.text)
    }

    fun `test the popup offers fixes that write the foreground`() {
        val controller = controllerFor("#777777 on #ffffff")
        val pair = pairAt(controller)
        val fixes = mutableListOf<ChromaColor>()
        val content = ContrastPopup.content(pair) { fixes += it }
        val links = UIUtil.findComponentsOfType(content, ActionLink::class.java)
        assertEquals(2, links.size)
        links.first().doClick()
        assertEquals(ChromaColor.fromRgb255(0x76, 0x76, 0x76), fixes.single())
    }

    fun `test a passing pair offers only the fixes it needs`() {
        val controller = controllerFor("#767676 on #ffffff")
        val links = UIUtil.findComponentsOfType(ContrastPopup.content(pairAt(controller)) {}, ActionLink::class.java)
        assertEquals(1, links.size)
        val black = controllerFor("#000000 on #ffffff")
        assertEmpty(UIUtil.findComponentsOfType(ContrastPopup.content(pairAt(black)) {}, ActionLink::class.java))
    }

    fun `test the badge opens the popup for its pair, also after an edit moves it`() {
        val controller = controllerFor("#777777 on #ffffff")
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "x ") }
        controller.refresh()
        val location = RelativePoint(myFixture.editor.contentComponent, Point(0, 0))
        try {
            controller.activateAnnotation(controller.annotationInlays.single(), location)
            assertEquals(pairAt(controller), ContrastPopup.shownPair)
            assertEquals(TextRange(2, 9), ContrastPopup.shownPair!!.foreground.range)
        } finally {
            ContrastPopup.close()
        }
    }

    fun `test check contrast takes two carets or a selection of two colors`() {
        val controller = controllerFor("#111111 x <caret>#222222 y #333333 <caret>z")
        assertNull(CheckContrastAction.chosenPair(myFixture.editor, controller.shownMatches))

        myFixture.configureByText("b.md", "<caret>#111111 x #222222 y <caret>#333333")
        val second = EditorLensController.getOrCreate(myFixture.editor, ChromaboxHost).also { it.refresh() }
        val byCarets = CheckContrastAction.chosenPair(myFixture.editor, second.shownMatches)!!
        assertEquals(TextRange(0, 7) to TextRange(20, 27), byCarets.foreground.range to byCarets.background.range)

        myFixture.configureByText("c.md", "#111111 <selection>x #222222 y #333333</selection>")
        val third = EditorLensController.getOrCreate(myFixture.editor, ChromaboxHost).also { it.refresh() }
        val bySelection = CheckContrastAction.chosenPair(myFixture.editor, third.shownMatches)!!
        assertEquals(TextRange(10, 17) to TextRange(20, 27), bySelection.foreground.range to bySelection.background.range)
    }
}
