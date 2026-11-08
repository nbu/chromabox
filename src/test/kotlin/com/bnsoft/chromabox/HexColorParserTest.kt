package com.bnsoft.chromabox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Color

class HexColorParserTest {
    private fun hexes(text: String, shortForms: Boolean = true) =
        HexColorParser.findAll(text, shortForms).map { text.substring(it.range.startOffset, it.range.endOffset) }

    @Test
    fun `finds all supported lengths`() {
        assertEquals(listOf("#fff", "#0f08", "#1E90FF", "#1e90ffcc"), hexes("#fff, #0f08 | #1E90FF;#1e90ffcc"))
    }

    @Test
    fun `parses channels and alpha`() {
        val (rgb, rgba) = HexColorParser.findAll("#1E90FF #0f08")
        assertEquals(Color(0x1E, 0x90, 0xFF), rgb.color)
        assertEquals(Color(0x00, 0xFF, 0x00, 0x88), rgba.color)
        assertEquals(HexFormat(6, upperCase = true), rgb.format)
    }

    @Test
    fun `rejects lookalikes`() {
        assertEquals(emptyList<String>(), hexes("&#123; foo#fff page#abc ##fff #ggg #12345 #fffz # Heading"))
    }

    @Test
    fun `finds colors in csv and markdown context`() {
        assertEquals(listOf("#ff0000", "#00ff00"), hexes("red,#ff0000\n| green | `#00ff00` |"))
    }

    @Test
    fun `short forms can be disabled`() {
        assertEquals(listOf("#aabbcc"), hexes("#bad #cafe #aabbcc", shortForms = false))
    }

    @Test
    fun `parse validates whole text`() {
        assertEquals(Color(255, 0, 0), HexColorParser.parse("#f00"))
        assertNull(HexColorParser.parse("#f00 "))
        assertNull(HexColorParser.parse("f00"))
        assertNull(HexColorParser.parse("#12345"))
    }

    @Test
    fun `format keeps case and length`() {
        assertEquals("#00FF88", HexColorParser.format(Color(0x00, 0xFF, 0x88), HexFormat(6, upperCase = true)))
        assertEquals("#0f8", HexColorParser.format(Color(0x00, 0xFF, 0x88), HexFormat(3, upperCase = false)))
        assertEquals("#1e90ff", HexColorParser.format(Color(0x1E, 0x90, 0xFF), HexFormat(3, upperCase = false)))
    }

    @Test
    fun `format adds alpha only when needed`() {
        assertEquals("#ff000080", HexColorParser.format(Color(255, 0, 0, 0x80), HexFormat(6, upperCase = false)))
        assertEquals("#f00f", HexColorParser.format(Color(255, 0, 0), HexFormat(4, upperCase = false)))
        assertEquals("#ff0000ff", HexColorParser.format(Color(255, 0, 0), HexFormat(8, upperCase = false)))
    }
}
