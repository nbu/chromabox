package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ColorVision
import com.bnsoft.chromabox.markdown.ChromaboxPreviewExtension
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension
import org.intellij.plugins.markdown.ui.preview.BrowserPipe
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import javax.swing.JComponent
import javax.swing.JPanel

class PreviewSwatchesTest : BasePlatformTestCase() {
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
            settings.state.showInMarkdownPreview = true
        } finally {
            super.tearDown()
        }
    }

    private fun swatches(text: String): List<PreviewSwatches.Swatch> {
        myFixture.configureByText("a.md", text)
        val file = myFixture.file.virtualFile
        return runReadAction { PreviewSwatches.find(myFixture.editor.document, file.fileType, project) }
    }

    fun `test every literal once, with its background, gradients wide`() {
        val found = swatches("# #ff0000 and #ff0000\n`rgb(0 0 255 / 50%)` in code\n\nbg: linear-gradient(#000, #fff 80%)\n```css\na { color: #00ff00 }\n```\n")
        assertEquals(
            listOf(
                PreviewSwatches.Swatch("#ff0000", "linear-gradient(rgba(255, 0, 0, 1.000), rgba(255, 0, 0, 1.000))"),
                PreviewSwatches.Swatch("rgb(0 0 255 / 50%)", "linear-gradient(rgba(0, 0, 255, 0.502), rgba(0, 0, 255, 0.502))"),
                PreviewSwatches.Swatch(
                    "linear-gradient(#000, #fff 80%)",
                    "linear-gradient(to right, rgba(0, 0, 0, 1.000) 0.00%, rgba(255, 255, 255, 1.000) 80.00%)",
                    wide = true,
                ),
                // The IDE shows a swatch for CSS in a code fence in the editor, but not in the preview.
                PreviewSwatches.Swatch("#00ff00", "linear-gradient(rgba(0, 255, 0, 1.000), rgba(0, 255, 0, 1.000))"),
            ),
            found,
        )
    }

    fun `test none in gutter or off mode, or when turned off`() {
        settings.mode = DisplayMode.GUTTER
        assertEmpty(swatches("#ff0000"))
        settings.mode = DisplayMode.OFF
        assertEmpty(swatches("#ff0000"))
        settings.mode = DisplayMode.BOX_ONLY
        assertEquals(1, swatches("#ff0000").size)
        settings.state.showInMarkdownPreview = false
        assertEmpty(swatches("#ff0000"))
    }

    fun `test the simulation applies`() {
        try {
            ColorVisionSimulation.select(ColorVision.ACHROMATOPSIA)
            assertEquals("linear-gradient(rgba(127, 127, 127, 1.000), rgba(127, 127, 127, 1.000))", swatches("#ff0000").single().background)
        } finally {
            ColorVisionSimulation.select(ColorVision.NONE)
        }
    }

    fun `test json escapes the texts`() {
        val json = PreviewSwatches.json(listOf(PreviewSwatches.Swatch("a\"b\\c\n", "x", wide = true), PreviewSwatches.Swatch("#fff", "y")))
        assertEquals("""{"swatches":[{"text":"a\"b\\c\u000a","background":"x","wide":true},{"text":"#fff","background":"y","wide":false}]}""", json)
    }

    fun `test the extension is registered and answers the script's requests`() {
        assertTrue(MarkdownBrowserPreviewExtension.Provider.all.any { it is ChromaboxPreviewExtension.Provider })
        myFixture.configureByText("a.md", "#ff0000")
        val pipe = FakePipe()
        val panel = FakePanel(myFixture.file.virtualFile, pipe)
        val extension = ChromaboxPreviewExtension.Provider().createBrowserExtension(panel)!!
        try {
            assertEquals(listOf("chromabox-preview.js"), extension.scripts)
            assertTrue(extension.resourceProvider.canProvide("chromabox-preview.css"))
            assertNotNull(extension.resourceProvider.loadResource("chromabox-preview.js"))
            assertNotNull(extension.resourceProvider.loadResource("chromabox-preview.css"))

            pipe.handlers.getValue("chromabox/request").processMessageReceived("")
            PlatformTestUtil.waitWithEventsDispatching("no swatches sent", { pipe.sent.isNotEmpty() }, 10)
            assertEquals("chromabox/swatches" to PreviewSwatches.json(listOf(PreviewSwatches.Swatch("#ff0000", "linear-gradient(rgba(255, 0, 0, 1.000), rgba(255, 0, 0, 1.000))"))), pipe.sent.last())

            // Settings changes are sent without a request.
            pipe.sent.clear()
            settings.mode = DisplayMode.OFF
            PlatformTestUtil.waitWithEventsDispatching("no swatches sent", { pipe.sent.isNotEmpty() }, 10)
            assertEquals("chromabox/swatches" to """{"swatches":[]}""", pipe.sent.last())
        } finally {
            Disposer.dispose(extension)
        }
        assertEmpty(pipe.handlers.keys)
    }

    private class FakePipe : BrowserPipe {
        val handlers = HashMap<String, BrowserPipe.Handler>()
        val sent: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(ArrayList())

        override fun send(type: String, data: String) {
            sent += type to data
        }

        override fun subscribe(type: String, handler: BrowserPipe.Handler) {
            handlers[type] = handler
        }

        override fun removeSubscription(type: String, handler: BrowserPipe.Handler) {
            handlers.remove(type, handler)
        }

        override fun dispose() = Unit
    }

    private class FakePanel(private val file: VirtualFile, private val pipe: BrowserPipe) : MarkdownHtmlPanel {
        override fun getComponent(): JComponent = JPanel()
        override fun setHtml(html: String, initialScrollOffset: Int, document: VirtualFile?) = Unit
        override fun getBrowserPipe() = pipe
        override fun getVirtualFile() = file
        override fun reloadWithOffset(offset: Int) = Unit
        override fun addScrollListener(listener: MarkdownHtmlPanel.ScrollListener?) = Unit
        override fun removeScrollListener(listener: MarkdownHtmlPanel.ScrollListener?) = Unit
        override suspend fun scrollTo(editor: Editor, line: Int) = Unit
        override fun dispose() = Unit
    }
}
