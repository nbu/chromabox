package com.bnsoft.chromabox

import com.bnsoft.chromabox.engine.EditorLensController
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class IdeColorSwatchesTest : LightJavaCodeInsightFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        // The mock JDK has no java.awt.
        myFixture.addClass("package java.awt; public class Color { public Color(int r, int g, int b) {} }")
    }

    private fun matchRanges(fileName: String, text: String): List<TextRange> {
        myFixture.configureByText(fileName, text)
        return EditorLensController.getOrCreate(myFixture.editor, ChromaboxHost).also { it.refresh() }.matchRanges
    }

    fun `test AWT colors in Java get no second swatch`() {
        val text = """
            import java.awt.Color;
            class A {
                Color red = new Color(255, 0, 0);
                String green = "#00ff00";
            }
        """.trimIndent()
        val green = text.indexOf("#00ff00")
        assertEquals(listOf(TextRange(green, green + 7)), matchRanges("A.java", text))
    }

    fun `test a class named Color that isn't AWT keeps its swatch`() {
        val text = """
            class A {
                static class Color { Color(int r, int g, int b) {} }
                Color red = new Color(255, 0, 0);
            }
        """.trimIndent()
        val start = text.indexOf("new Color")
        assertEquals(listOf(TextRange(start, start + "new Color(255, 0, 0)".length)), matchRanges("A.java", text))
    }

    fun `test colors in files without a color provider keep their swatch`() {
        assertEquals(listOf(TextRange(0, 20)), matchRanges("a.txt", "new Color(255, 0, 0)"))
    }
}
