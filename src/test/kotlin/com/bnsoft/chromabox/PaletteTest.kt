package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.palette.ColorIndex
import com.bnsoft.chromabox.palette.CopyFormat
import com.bnsoft.chromabox.palette.IndexedColor
import com.bnsoft.chromabox.palette.Palette
import com.bnsoft.chromabox.palette.PaletteActions
import com.bnsoft.chromabox.palette.PalettePanel
import com.bnsoft.chromabox.palette.PaletteScope
import com.bnsoft.chromabox.palette.PaletteSort
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex

class PaletteTest : BasePlatformTestCase() {
    private val settings get() = ChromaboxSettings.getInstance()

    override fun setUp() {
        super.setUp()
        settings.setLensEnabled(ColorFormat.NAMED.lensId, true)
    }

    override fun tearDown() {
        try {
            settings.setLensEnabled(ColorFormat.NAMED.lensId, false)
        } finally {
            super.tearDown()
        }
    }

    private fun entries(file: VirtualFile) = runReadAction {
        Palette.entries(Palette.scan(file, FileDocumentManager.getInstance().getDocument(file)!!, project))
    }

    private fun summary(file: VirtualFile) = entries(file).map { e -> Triple(e.hex, e.occurrences.map { it.text }, e.formats) }

    fun `test colors written differently are one entry, gradient stops count`() {
        val file = myFixture.configureByText("a.md", "#FF0000 rgb(255, 0, 0) red\n#0f08 and linear-gradient(red, #000 50%)\n").virtualFile
        assertEquals(
            listOf(
                Triple("#ff0000", listOf("#FF0000", "rgb(255, 0, 0)", "red", "red"), listOf(ColorFormat.HEX, ColorFormat.CSS_FUNCTIONS, ColorFormat.NAMED, ColorFormat.GRADIENTS)),
                Triple("#000000", listOf("#000"), listOf(ColorFormat.GRADIENTS)),
                Triple("#00ff0088", listOf("#0f08"), listOf(ColorFormat.HEX)),
            ),
            summary(file),
        )
        val stop = entries(file)[0].occurrences.last()
        assertEquals("red", myFixture.editor.document.getText(stop.range))
    }

    fun `test sorting by hue puts grays last, by lightness dark to light`() {
        val file = myFixture.configureByText("a.md", "#0000ff #ffffff #ff0000 #00ff00 #000000 #808080\n").virtualFile
        val entries = entries(file)
        assertEquals(listOf("#ff0000", "#00ff00", "#0000ff", "#000000", "#808080", "#ffffff"), Palette.sorted(entries, PaletteSort.HUE).map { it.hex })
        assertEquals(listOf("#000000", "#0000ff", "#808080", "#ff0000", "#00ff00", "#ffffff"), Palette.sorted(entries, PaletteSort.LIGHTNESS).map { it.hex })
    }

    fun `test copy as`() {
        val orange = ChromaColor.fromRgb255(255, 128, 0)
        assertEquals(
            listOf("#ff8000", "rgb(255 128 0)", "hsl(30.1 100% 50%)", "oklch(73.19% 0.1858 52.98)", "Color(0xFFFF8000)", "Color(red: 1, green: 0.502, blue: 0, opacity: 1)"),
            CopyFormat.entries.map { it.format(orange) },
        )
        val translucent = ChromaColor.fromRgb255(0, 0, 0, 0.5)
        assertEquals("#00000080", CopyFormat.HEX.format(translucent))
        assertEquals("rgb(0 0 0 / 0.502)", CopyFormat.RGB.format(translucent))
        assertEquals("hsl(0 0% 0% / 0.502)", CopyFormat.HSL.format(translucent))
        assertEquals("Color(0x80000000)", CopyFormat.COMPOSE.format(translucent))
    }

    fun `test replace writes each occurrence in its notation, in every file, as one undo step`() {
        val first = myFixture.addFileToProject("a.md", "fg: #FF0000; bg: rgb(255, 0, 0)\n").virtualFile
        val second = myFixture.addFileToProject("b.md", "red and linear-gradient(red, #fff)\n").virtualFile
        val result = PaletteActions.replace(project, listOf(first, second), "#ff0000", ChromaColor.fromRgb255(0, 128, 0))
        val documents = FileDocumentManager.getInstance()
        assertEquals("fg: #008000; bg: rgb(0, 128, 0)\n", documents.getDocument(first)!!.text)
        // "green" is #008000, so even the named colors can be written.
        assertEquals("green and linear-gradient(green, #fff)\n", documents.getDocument(second)!!.text)
        assertEquals(PaletteActions.ReplaceResult(written = 4, unrepresentable = 0, readOnlyFiles = 0), result)

        myFixture.openFileInEditor(first)
        val editor = FileEditorManager.getInstance(project).getSelectedEditor(first) as TextEditor
        UndoManager.getInstance(project).undo(editor)
        assertEquals("fg: #FF0000; bg: rgb(255, 0, 0)\n", documents.getDocument(first)!!.text)
        assertEquals("red and linear-gradient(red, #fff)\n", documents.getDocument(second)!!.text)
    }

    fun `test replace writes what's there now`() {
        val file = myFixture.configureByText("a.md", "red #ff0000 #f00\n").virtualFile
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.replaceString(12, 16, "#f01 #f00") }
        val result = PaletteActions.replace(project, listOf(file), "#ff0000", ChromaColor.fromRgb255(1, 2, 3))
        // A named color without a name for the new color is written in hex, like in the editor.
        assertEquals("#010203 #010203 #f01 #010203\n", myFixture.editor.document.text)
        assertEquals(PaletteActions.ReplaceResult(written = 3, unrepresentable = 0, readOnlyFiles = 0), result)
    }

    fun `test the panel lists the current file's colors and follows edits, only while shown`() {
        myFixture.configureByText("a.md", "#ff0000 #00ff00 #ff0000\n")
        var showing = false
        val panel = PalettePanel(project) { showing }
        Disposer.register(testRootDisposable, panel)
        assertNull(panel.refresh())
        assertEmpty(panel.entries)

        showing = true
        PlatformTestUtil.waitForPromise(panel.refresh()!!)
        assertEquals(listOf("#ff0000" to 2, "#00ff00" to 1), panel.entries.map { it.hex to it.occurrences.size })

        panel.sort = PaletteSort.HUE
        assertEquals(listOf("#ff0000", "#00ff00"), panel.entries.map { it.hex })

        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "#0000ff ") }
        PlatformTestUtil.waitForPromise(panel.refresh()!!)
        assertEquals(listOf("#ff0000", "#00ff00", "#0000ff"), panel.entries.map { it.hex })

        val other = myFixture.addFileToProject("b.md", "#123456").virtualFile
        panel.scope = PaletteScope.OPEN_FILES
        myFixture.openFileInEditor(other)
        PlatformTestUtil.waitForPromise(panel.refresh()!!)
        assertEquals(setOf("#ff0000", "#00ff00", "#0000ff", "#123456"), panel.entries.map { it.hex }.toSet())
    }

    private fun indexed(file: VirtualFile): Map<String, List<IndexedColor>> =
        FileBasedIndex.getInstance().getFileData(ColorIndex.NAME, file, project)

    fun `test the index maps each color to where a file writes it, gradient stops too`() {
        val file = myFixture.addFileToProject("a.md", "#FF0000 rgb(255, 0, 0)\nlinear-gradient(#000, red)\n").virtualFile
        assertEquals(
            mapOf(
                "#ff0000" to listOf(IndexedColor(0, 7, ColorFormat.HEX), IndexedColor(8, 22, ColorFormat.CSS_FUNCTIONS), IndexedColor(45, 48, ColorFormat.GRADIENTS)),
                "#000000" to listOf(IndexedColor(39, 43, ColorFormat.GRADIENTS)),
            ),
            indexed(file),
        )
        // Skipped file types aren't indexed.
        assertEmpty(indexed(myFixture.addFileToProject("a.css", "a { color: #ff0000 }").virtualFile).keys)
    }

    fun `test the index follows unsaved edits`() {
        val file = myFixture.configureByText("a.md", "#ff0000").virtualFile
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("x #00ff00") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(mapOf("#00ff00" to listOf(IndexedColor(2, 9, ColorFormat.HEX))), indexed(file))
    }

    fun `test the fingerprint changes with the settings the index depends on, and not with others`() {
        val before = ColorIndex.settingsFingerprint()
        val mode = settings.mode
        try {
            settings.mode = if (mode == DisplayMode.OFF) DisplayMode.BOX_ONLY else DisplayMode.OFF
            assertEquals(before, ColorIndex.settingsFingerprint())
        } finally {
            settings.mode = mode
        }
        settings.setLensEnabled(ColorFormat.NAMED.lensId, false)
        assertFalse(before == ColorIndex.settingsFingerprint())
        settings.setLensEnabled(ColorFormat.NAMED.lensId, true)
        val shortForms = settings.state.shortForms
        try {
            settings.state.shortForms = !shortForms
            assertFalse(before == ColorIndex.settingsFingerprint())
        } finally {
            settings.state.shortForms = shortForms
        }
    }

    fun `test project and custom scopes list colors of files that aren't open, from the index`() {
        val a = myFixture.addFileToProject("a.md", "#ff0000 #00ff00").virtualFile
        myFixture.addFileToProject("dir/b.md", "#ff0000")
        val panel = PalettePanel(project) { true }
        Disposer.register(testRootDisposable, panel)
        panel.scope = PaletteScope.PROJECT
        PlatformTestUtil.waitForPromise(panel.refresh()!!)
        assertEquals(listOf("#ff0000" to 2, "#00ff00" to 1), panel.entries.map { it.hex to it.occurrences.size })
        assertEquals(setOf("a.md", "b.md"), panel.entries[0].occurrences.map { it.file.name }.toSet())

        panel.scope = PaletteScope.CUSTOM
        assertNull(panel.refresh())
        assertEmpty(panel.entries)
        panel.customScopeOverride = GlobalSearchScope.filesScope(project, listOf(a))
        PlatformTestUtil.waitForPromise(panel.refresh()!!)
        assertEquals(listOf("#00ff00" to 1, "#ff0000" to 1), panel.entries.map { it.hex to it.occurrences.size })
    }
}
