package com.bnsoft.chromabox

import com.bnsoft.chromabox.engine.EditorLensController
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

class ChromaboxSettingsTest : BasePlatformTestCase() {
    private val settings get() = ChromaboxSettings.getInstance()
    private lateinit var saved: ChromaboxSettings.SettingsState

    override fun setUp() {
        super.setUp()
        saved = copy(settings.state)
        settings.loadState(ChromaboxSettings.SettingsState())
    }

    override fun tearDown() {
        try {
            settings.loadState(saved)
            settings.notifyChanged()
        } finally {
            super.tearDown()
        }
    }

    private fun copy(state: ChromaboxSettings.SettingsState) =
        XmlSerializer.deserialize(XmlSerializer.serialize(state), ChromaboxSettings.SettingsState::class.java)

    fun `test settings saved by 1_0 keep their meaning`() {
        val xml = """
            <ChromaboxSettings>
              <option name="mode" value="BOX_ONLY" />
              <option name="shortForms" value="false" />
            </ChromaboxSettings>
        """.trimIndent()
        val state = XmlSerializer.deserialize(JDOMUtil.load(xml), ChromaboxSettings.SettingsState::class.java)
        settings.loadState(state)

        assertEquals(DisplayMode.BOX_ONLY, settings.mode)
        assertFalse(settings.state.shortForms)
        for (format in ColorFormat.entries) assertEquals(format.name, format.enabledByDefault, settings.isLensEnabled(format.lensId))
    }

    fun `test only overrides of the defaults are stored`() {
        settings.setLensEnabled(ColorFormat.NAMED.lensId, true)
        settings.setLensEnabled(ColorFormat.HEX.lensId, false)
        settings.setLensEnabled(ColorFormat.CSS_FUNCTIONS.lensId, true)
        assertEquals(mapOf("color.hex" to false, "color.named" to true), settings.state.lensOverrides)

        val restored = copy(settings.state)
        assertEquals(mapOf("color.hex" to false, "color.named" to true), restored.lensOverrides)

        settings.setLensEnabled(ColorFormat.HEX.lensId, true)
        assertEquals(mapOf("color.named" to true), settings.state.lensOverrides)
    }

    fun `test disabled lens shows nothing`() {
        myFixture.configureByText("a.md", "#ff0000")
        val controller = EditorLensController.getOrCreate(myFixture.editor, ChromaboxHost).also { it.refresh() }
        assertEquals(1, controller.matchRanges.size)

        settings.setLensEnabled(ColorFormat.HEX.lensId, false)
        settings.notifyChanged()
        controller.refresh()
        assertEmpty(controller.matchRanges)
    }
}
