package com.bnsoft.chromabox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Color

class IntegerAndConstructorColorsTest {
    private fun integers(text: String, order: IntColorOrder = IntColorOrder.ARGB) =
        IntegerColors.findAll(text, order).map { text.substring(it.range.startOffset, it.range.endOffset) }

    private fun constructors(text: String) =
        ConstructorColors.findAll(text).map { text.substring(it.range.startOffset, it.range.endOffset) }

    private fun pickInt(literal: String, color: Color) =
        IntegerColors.format(color, IntegerColors.parse(literal, IntColorOrder.ARGB)!!.second)

    private fun pick(literal: String, color: Color) = ConstructorColors.format(color, ConstructorColors.parse(literal)!!.second)

    private fun constructed(literal: String) = ConstructorColors.parse(literal)!!.first.toAwt()

    @Test
    fun `finds standalone 8-digit integers only`() {
        assertEquals(listOf("0xFF112233", "0x80aabbcc"), integers("val a = 0xFF112233; b = 0x80aabbccL; c = 0xAABBCC; d = 0x1234"))
        assertEquals(emptyList<String>(), integers("x0xFF112233 0xFF1122334 v1.0xFF112233"))
    }

    @Test
    fun `integer order follows the setting`() {
        assertEquals(Color(0x11, 0x22, 0x33, 0xFF), IntegerColors.findAll("0xFF112233", IntColorOrder.ARGB).single().color.toAwt())
        assertEquals(Color(0xFF, 0x11, 0x22, 0x33), IntegerColors.findAll("0xFF112233", IntColorOrder.RGBA).single().color.toAwt())
        val rgba = IntegerColors.parse("0x11223344", IntColorOrder.RGBA)!!.second
        assertEquals("0x55667788", IntegerColors.format(Color(0x55, 0x66, 0x77, 0x88), rgba))
    }

    @Test
    fun `integers keep prefix and case, and gain alpha`() {
        assertEquals("0xFF445566", pickInt("0xFF112233", Color(0x44, 0x55, 0x66)))
        assertEquals("0Xffaabbcc", pickInt("0Xff112233", Color(0xaa, 0xbb, 0xcc)))
        assertEquals("0x80AABBCC", pickInt("0x11AA33", Color(0xAA, 0xBB, 0xCC, 0x80)))
        assertEquals("0xAABBCC", pickInt("0x11AA33", Color(0xAA, 0xBB, 0xCC)))
    }

    @Test
    fun `finds constructors`() {
        val text = """
            Color(0xFF112233) Color(0x112233u) Color.fromARGB(255, 1, 2, 3) new Color(1, 2, 3) new Color(1,2,3,4)
            UIColor(red: 0.1, green: 0.2, blue: 0.3, alpha: 1) Color(red: 1, green: 0, blue: 0) NSColor(red: 1, green: 1, blue: 1)
        """.trimIndent()
        assertEquals(
            listOf(
                "Color(0xFF112233)", "Color(0x112233u)", "Color.fromARGB(255, 1, 2, 3)", "new Color(1, 2, 3)", "new Color(1,2,3,4)",
                "UIColor(red: 0.1, green: 0.2, blue: 0.3, alpha: 1)", "Color(red: 1, green: 0, blue: 0)", "NSColor(red: 1, green: 1, blue: 1)",
            ),
            constructors(text).sortedBy { text.indexOf(it) },
        )
    }

    @Test
    fun `rejects out-of-range and unrelated calls`() {
        for (bad in listOf("new Color(256, 0, 0)", "UIColor(red: 1.5, green: 0, blue: 0)", "MyColor(0xFF112233)", "Color(0x1234)", "x.Color(0xFF112233)")) {
            assertNull(bad, ConstructorColors.parse(bad))
        }
    }

    @Test
    fun `parses constructor channels`() {
        assertEquals(Color(0x11, 0x22, 0x33), constructed("Color(0xFF112233)"))
        assertEquals(Color(1, 2, 3, 128), constructed("Color.fromARGB(128, 1, 2, 3)"))
        assertEquals(Color(1, 2, 3, 4), constructed("new Color(1,2,3,4)"))
        assertEquals(Color(255, 128, 0, 128), constructed("UIColor(red: 1, green: 0.5, blue: 0, alpha: 0.5)"))
    }

    @Test
    fun `acceptance examples from the spec`() {
        assertEquals("0xFF445566", pickInt("0xFF112233", Color(0x44, 0x55, 0x66)))
        assertEquals("Color(0x80445566)", pick("Color(0xff112233)", Color(0x44, 0x55, 0x66, 0x80)))
        assertEquals(
            "UIColor(red: 0, green: 0, blue: 1, alpha: 1)",
            pick("UIColor(red: 1, green: 0, blue: 0, alpha: 1)", Color(0, 0, 255)),
        )
    }

    @Test
    fun `constructors keep their style and gain alpha`() {
        assertEquals("Color(0x80aabbcc)", pick("Color(0x112233)", Color(0xaa, 0xbb, 0xcc, 0x80)))
        assertEquals("Color.fromARGB(128, 4, 5, 6)", pick("Color.fromARGB(255, 1, 2, 3)", Color(4, 5, 6, 128)))
        assertEquals("new Color(4,  5, 6, 128)", pick("new Color(1,  2, 3)", Color(4, 5, 6, 128)))
        assertEquals("new Color(4, 5, 6)", pick("new Color(1, 2, 3)", Color(4, 5, 6)))
        assertEquals("Color(red: 1, green: 0.5, blue: 0, opacity: 0.5)", pick("Color(red: 0, green: 0, blue: 0)", Color(255, 128, 0, 128)))
        assertEquals("NSColor(red: .5, green: .25, blue: 0)", pick("NSColor(red: .1, green: .1, blue: .1)", Color(128, 64, 0)))
    }

    @Test
    fun `float channels use the fewest decimals that give the picked channel`() {
        val written = pick("UIColor(red: 0, green: 0, blue: 0)", Color(0x12, 0x34, 0x56))
        assertEquals(Color(0x12, 0x34, 0x56), constructed(written))
        assertEquals("UIColor(red: 0.07, green: 0.204, blue: 0.337)", written)
    }
}
