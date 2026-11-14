package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Color

class CssColorFunctionsTest {
    private fun found(text: String) = CssColorFunctions.findAll(text).map { text.substring(it.range.startOffset, it.range.endOffset) }

    private fun color(literal: String) = CssColorFunctions.parse(literal)!!.first.toAwt()

    /** Writes the color [hex] (`#RRGGBB` or `#RRGGBBAA`) the way [literal] is written. */
    private fun pick(literal: String, hex: String): String {
        val format = CssColorFunctions.parse(literal)!!.second
        return CssColorFunctions.format(ChromaColor.fromAwt(HexColorParser.parse(hex)!!), format)
    }

    @Test
    fun `finds legacy and modern syntax`() {
        val text = "a rgb(255, 0, 0) b rgba(255,0,0,.5) c hsl(120deg 50% 50%) d hwb(10 20% 30% / 0.4) e HSLA(1, 2%, 3%, 4%)"
        assertEquals(
            listOf("rgb(255, 0, 0)", "rgba(255,0,0,.5)", "hsl(120deg 50% 50%)", "hwb(10 20% 30% / 0.4)", "HSLA(1, 2%, 3%, 4%)"),
            found(text),
        )
    }

    @Test
    fun `is word-bounded and rejects malformed arguments`() {
        assertEquals(emptyList<String>(), found("myrgb(1,2,3) x-rgb(1,2,3)"))
        for (bad in listOf(
            "rgb(1, 2)", "rgb(1, 2, 3, 4, 5)", "rgb(1 2, 3)", "rgb(1 2 3 4)", "rgb(1, 2, 3,)", "rgb(1 2 3 /)",
            "rgb(var(--x))", "rgb(1deg 2 3)", "hsl(1 2em 3%)", "hwb(1, 2%, 3%)", "rgb(1 2 3 / 4deg)", "rgb()",
        )) assertNull(bad, CssColorFunctions.parse(bad))
    }

    @Test
    fun `parses channels, percentages, hue units and alpha`() {
        assertEquals(Color(255, 0, 0), color("rgb(255, 0, 0)"))
        assertEquals(Color(255, 0, 0, 128), color("rgba(255,0,0,.5)"))
        assertEquals(Color(255, 128, 0, 128), color("rgb(100% 50% 0% / 50%)"))
        assertEquals(Color(64, 191, 64), color("hsl(120, 50%, 50%)"))
        assertEquals(color("hsl(120deg 50% 50%)"), color("hsl(0.3333333turn 50% 50%)"))
        assertEquals(color("hsl(180 50% 50%)"), color("hsl(200grad 50% 50%)"))
        assertEquals(color("hsl(180 50% 50%)"), color("hsl(3.14159265rad 50% 50%)"))
        assertEquals(Color(255, 0, 0), color("hwb(0 0% 0%)"))
        assertEquals(Color(128, 128, 128), color("hwb(90 50% 50%)"))
    }

    @Test
    fun `acceptance examples from the spec`() {
        assertEquals("rgb(0, 255, 0)", pick("rgb(255, 0, 0)", "#00ff00"))
        assertEquals("rgb(0 255 0 / 50%)", pick("rgb(255 0 0)", "#00ff0080"))
        assertEquals("HSL(0, 100%, 50%)", pick("HSL(120, 50%, 50%)", "#ff0000"))
    }

    @Test
    fun `keeps separators, whitespace and number style`() {
        assertEquals("rgb( 0 ,255 , 0 )", pick("rgb( 255 ,0 , 0 )", "#00ff00"))
        assertEquals("rgba(0,255,0,.5)", pick("rgba(255,0,0,.25)", "#00ff0080"))
        assertEquals("rgba(0, 255, 0, 0.5)", pick("rgba(255, 0, 0, 0.25)", "#00ff0080"))
        assertEquals("rgba(0, 255, 0, 0.50)", pick("rgba(255, 0, 0, 0.20)", "#00ff0080"))
        assertEquals("rgb(0% 100% 0%)", pick("rgb(100% 0% 0%)", "#00ff00"))
        assertEquals("hsl(0.0deg 100.0% 50.0%)", pick("hsl(120.0deg 50.0% 50.0%)", "#ff0000"))
        assertEquals("hsl(0.5turn 100% 50%)", pick("hsl(0.25turn 100% 50%)", "#00ffff"))
    }

    @Test
    fun `adds alpha in the style of the same syntax`() {
        assertEquals("rgba(0, 255, 0, 0.5)", pick("rgb(255, 0, 0)", "#00ff0080"))
        assertEquals("RGBA(0,255,0,0.5)", pick("RGB(255,0,0)", "#00ff0080"))
        assertEquals("hsla(120, 100%, 50%, 0.5)", pick("hsl(0, 100%, 50%)", "#00ff0080"))
        assertEquals("hwb(120 0% 0% / 50%)", pick("hwb(0 0% 0%)", "#00ff0080"))
        assertEquals("rgba(0, 255, 0, 1)", pick("rgba(255, 0, 0, 0.5)", "#00ff00"))
    }

    @Test
    fun `uses the fewest decimals that give the picked color`() {
        assertEquals("rgb(0 255 0 / 50%)", pick("rgb(0 0 0 / 1%)", "#00ff0080"))
        val written = pick("hsl(0 0% 0%)", "#1e90ff")
        assertEquals(Color(0x1e, 0x90, 0xff), color(written))
        assertEquals("hsl(209.6 100% 55.9%)", written)
    }

    @Test
    fun `achromatic colors keep the original hue`() {
        assertEquals("hsl(120, 0%, 50%)", pick("hsl(120, 50%, 50%)", "#808080"))
        assertEquals("hwb(200 50% 50%)", pick("hwb(200 10% 10%)", "#808080"))
    }

    @Test
    fun `round trip of an unchanged color is the identity`() {
        for (literal in listOf(
            "rgb(255, 0, 0)", "rgba(255,0,0,.5)", "rgb(255 0 0 / 50%)", "hsl(120deg 50% 50%)", "hsla(120, 50%, 50%, 0.5)",
            "hwb(0 0% 0%)", "rgb(100% 50% 0%)", "HSL(0, 100%, 50%)",
        )) {
            val (color, format) = CssColorFunctions.parse(literal)!!
            assertNotNull(literal, format)
            assertEquals(literal, CssColorFunctions.format(color, format))
        }
    }
}
