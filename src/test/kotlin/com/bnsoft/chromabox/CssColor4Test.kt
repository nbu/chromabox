package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

class CssColor4Test {
    private fun found(text: String, functions: Set<CssColorFunction> = CssColorFunction.entries.toSet()) =
        CssColorFunctions.findAll(text, functions).map { text.substring(it.range.startOffset, it.range.endOffset) }

    private fun parsed(literal: String) = CssColorFunctions.parse(literal)!!.first

    private fun pick(literal: String, color: Color): String =
        CssColorFunctions.format(ChromaColor.fromAwt(color), CssColorFunctions.parse(literal)!!.second)

    @Test
    fun `finds CSS Color 4 functions`() {
        val text = "lab(54 81 70) lch(54 107 41) oklab(0.63 0.22 0.13) OKLCH(70% 0.1 200 / 50%) " +
            "color(display-p3 1 0 0) color(srgb 0 0.5 1)"
        assertEquals(
            listOf("lab(54 81 70)", "lch(54 107 41)", "oklab(0.63 0.22 0.13)", "OKLCH(70% 0.1 200 / 50%)", "color(display-p3 1 0 0)", "color(srgb 0 0.5 1)"),
            found(text),
        )
    }

    @Test
    fun `each lens finds only its own level`() {
        val text = "rgb(1 2 3) oklch(0.5 0.1 200)"
        assertEquals(listOf("rgb(1 2 3)"), found(text, CssColorFunction.LEVEL_3))
        assertEquals(listOf("oklch(0.5 0.1 200)"), found(text, CssColorFunction.LEVEL_4))
    }

    @Test
    fun `rejects legacy syntax and unknown color spaces`() {
        for (bad in listOf(
            "oklch(0.5, 0.1, 200)", "lab(50, 10, 10)", "color(display-p3, 1, 0, 0)", "color(rec2020 1 0 0)",
            "color(display-p3 1 0)", "color(1 0 0)", "oklch(0.5 0.1deg 200)", "lab(50 10 10deg)",
        )) assertNull(bad, CssColorFunctions.parse(bad))
    }

    @Test
    fun `parses to the reference colors`() {
        assertEquals(Color(255, 0, 0), parsed("oklch(0.62796 0.25768 29.2339)").toAwt())
        assertEquals(Color(255, 0, 0), parsed("oklch(62.796% 64.42% 29.2339deg)").toAwt())
        assertEquals(Color(255, 0, 0), parsed("lab(54.29 80.8 69.89)").toAwt())
        assertEquals(Color(255, 0, 0), parsed("lch(54.29 106.84 40.85)").toAwt())
        assertEquals(Color(255, 0, 0), parsed("color(srgb 1 0 0)").toAwt())
        assertEquals(Color(255, 255, 255, 128), parsed("oklab(1 0 0 / 50%)").toAwt())
    }

    @Test
    fun `out-of-gamut colors are kept unclamped`() {
        val p3Red = parsed("color(display-p3 1 0 0)")
        assertFalse(p3Red.inGamut)
        assertEquals(Color(255, 0, 0), p3Red.toAwt())
        assertFalse(parsed("oklch(70% 0.4 200)").inGamut)
        assertTrue(parsed("oklch(70% 0.1 200)").inGamut)
        assertTrue(ColorSwatchRenderer.of(p3Red).outOfGamut)
        assertFalse(ColorSwatchRenderer.of(parsed("oklch(70% 0.1 200)")).outOfGamut)
    }

    @Test
    fun `written colors give the picked color and keep units`() {
        val picks = listOf(Color(255, 0, 0), Color(0x1e, 0x90, 0xff), Color(0x12, 0x34, 0x56, 0x80), Color(250, 250, 250))
        for (literal in listOf(
            "oklch(70% 0.1 200)", "oklch(0.7 0.1 200deg)", "oklab(0.7 -0.1 0.1)", "lab(50 20 -30)",
            "lch(50% 30 120)", "color(display-p3 0.5 0.5 0.5)", "color(srgb 0.5 0.5 0.5 / 1)",
        )) for (color in picks) {
            val written = pick(literal, color)
            assertEquals("$literal → $written", color, parsed(written).toAwt())
            assertEquals(written, literal.substringBefore('('), written.substringBefore('('))
            assertEquals(written, literal.count { it == '%' } + (if (color.alpha < 255 && "/" !in literal) 1 else 0), written.count { it == '%' })
            assertEquals(written, "deg" in literal, "deg" in written)
        }
    }

    @Test
    fun `respects the decimal limits`() {
        val written = pick("oklch(0.5 0.1 200)", Color(0x1e, 0x90, 0xff))
        val (l, c, h) = written.removePrefix("oklch(").removeSuffix(")").split(' ')
        assertTrue(written, l.substringAfter('.', "").length <= 3)
        assertTrue(written, c.substringAfter('.', "").length <= 3)
        assertTrue(written, h.substringAfter('.', "").length <= 1)
    }

    @Test
    fun `round trip of an unchanged in-gamut color is the identity`() {
        for (literal in listOf("oklch(70% 0.1 200)", "lab(50 20 -30)", "color(srgb 0.5 0.25 1)", "oklab(0.7 0 0 / 50%)")) {
            val (color, format) = CssColorFunctions.parse(literal)!!
            assertEquals(literal, CssColorFunctions.format(color, format))
        }
    }

    @Test
    fun `adds alpha with a slash`() {
        assertEquals("oklch(62.8% 0.258 29.2 / 50%)", pick("oklch(70% 0.1 200)", Color(255, 0, 0, 128)))
    }

    @Test
    fun `picker events with the initial color are not written`() {
        val written = ArrayList<Color>()
        val initial = Color(255, 0, 0)
        val listener = ColorLensEditor.pickerListener(initial) { written += it.toAwt() }
        listener.colorChanged(initial, null)
        assertEquals(emptyList<Color>(), written)
        listener.colorChanged(Color(0, 255, 0), null)
        listener.colorChanged(initial, null)
        assertEquals(listOf(Color(0, 255, 0), initial), written)
    }
}
