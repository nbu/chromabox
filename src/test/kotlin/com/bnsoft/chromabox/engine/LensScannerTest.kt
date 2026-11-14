package com.bnsoft.chromabox.engine

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LensScannerTest : BasePlatformTestCase() {
    /** Matches every occurrence of [pattern]; the value is the lens id, so results show which lens won. */
    private class RegexLens(override val id: String, pattern: String) : Lens<String> {
        private val regex = Regex(pattern)

        override fun find(text: CharSequence, context: LensContext) =
            regex.findAll(text).map { LensMatch(TextRange(it.range.first, it.range.last + 1), id, Unit) }.toList()

        override fun renderer(match: LensMatch<String>) = object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>) = 1
        }

        override fun editor(match: LensMatch<String>): LensEditor<String>? = null
    }

    private fun scan(text: String, vararg lenses: Lens<*>): List<Pair<String, String>> {
        val context = LensContext(PlainTextFileType.INSTANCE, null, DocumentImpl(text))
        return LensScanner.scan(text, context, lenses.toList()).map { it.match.value as String to text.substring(it.range.startOffset, it.range.endOffset) }
    }

    fun `test longest match wins`() {
        val result = scan("rgb(0 0 0 / 50%) #fff", RegexLens("short", """\d+%?|#\w+"""), RegexLens("func", """rgb\([^)]*\)"""))
        assertEquals(listOf("func" to "rgb(0 0 0 / 50%)", "short" to "#fff"), result)
    }

    fun `test first lens wins ties`() {
        assertEquals(listOf("a" to "abc"), scan("abc", RegexLens("a", "abc"), RegexLens("b", "abc")))
        assertEquals(listOf("b" to "abc"), scan("abc", RegexLens("b", "abc"), RegexLens("a", "abc")))
    }

    fun `test partial overlaps keep the longer match`() {
        assertEquals(listOf("long" to "bcde"), scan("abcdef", RegexLens("short", "ab"), RegexLens("long", "bcde")))
    }

    fun `test adjacent matches are both kept`() {
        assertEquals(listOf("x" to "ab", "y" to "cd"), scan("abcd", RegexLens("x", "ab"), RegexLens("y", "cd")))
    }
}
