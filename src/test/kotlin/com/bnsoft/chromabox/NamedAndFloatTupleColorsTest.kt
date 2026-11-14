package com.bnsoft.chromabox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

class NamedAndFloatTupleColorsTest {
    private fun names(text: String) = NamedColors.findAll(text).map { text.substring(it.range.startOffset, it.range.endOffset) }

    private fun tuples(text: String) = FloatTuples.findAll(text).map { text.substring(it.range.startOffset, it.range.endOffset) }

    private fun pickName(literal: String, color: Color) = NamedColors.format(color, NamedColors.parse(literal)!!.second)

    private fun pickTuple(literal: String, color: Color) = ConstructorColors.format(color, FloatTuples.parse(literal)!!.second)

    @Test
    fun `all CSS named colors`() {
        assertEquals(148, NamedColors.COLORS.size)
        assertEquals(Color(0x66, 0x33, 0x99), NamedColors.parse("rebeccapurple")!!.first.toAwt())
        assertEquals(Color(0xff, 0x63, 0x47), NamedColors.parse("Tomato")!!.first.toAwt())
    }

    @Test
    fun `finds names in any case, word-bounded`() {
        assertEquals(listOf("Tomato", "REBECCAPURPLE", "darkred", "red"), names("Tomato, REBECCAPURPLE and darkred; the red car"))
        assertEquals(emptyList<String>(), names("text-red red-500 redness bored _red red_ \$red @red #red"))
    }

    @Test
    fun `writes a name in the original case if the color has one`() {
        assertEquals("red", pickName("tomato", Color(255, 0, 0)))
        assertEquals("Red", pickName("Tomato", Color(255, 0, 0)))
        assertEquals("RED", pickName("TOMATO", Color(255, 0, 0)))
        assertEquals("rebeccapurple", pickName("red", Color(0x66, 0x33, 0x99)))
    }

    @Test
    fun `keeps the original among names of the same color, and its spelling of grey`() {
        assertEquals("cyan", pickName("cyan", Color(0, 255, 255)))
        assertEquals("aqua", pickName("aqua", Color(0, 255, 255)))
        assertEquals("aqua", pickName("red", Color(0, 255, 255)))
        assertEquals("grey", pickName("darkgrey", Color(0x80, 0x80, 0x80)))
        assertEquals("gray", pickName("darkgray", Color(0x80, 0x80, 0x80)))
        assertEquals("gray", pickName("red", Color(0x80, 0x80, 0x80)))
    }

    @Test
    fun `writes hex for colors without a name, except after a dot`() {
        assertEquals("#123456", pickName("tomato", Color(0x12, 0x34, 0x56)))
        assertEquals("#ABCDEF", pickName("TOMATO", Color(0xab, 0xcd, 0xef)))
        assertEquals("#ff000080", pickName("red", Color(255, 0, 0, 128)))

        val member = NamedColors.findAll("UIColor.red").single().format
        assertTrue(member.member)
        assertEquals("blue", NamedColors.format(Color(0, 0, 255), member))
        assertNull(NamedColors.format(Color(0x12, 0x34, 0x56), member))
    }

    @Test
    fun `finds float tuples`() {
        val text = "a = [0.1, 0.2, 0.3]; b = (1.0, 0.5, 0.0, 1.0); c = [.5,0,1]; d = ( 0.25 , 1 , 0 )"
        assertEquals(listOf("[0.1, 0.2, 0.3]", "(1.0, 0.5, 0.0, 1.0)", "[.5,0,1]", "( 0.25 , 1 , 0 )"), tuples(text))
    }

    @Test
    fun `rejects integer tuples, calls, other sizes and values outside 0 to 1`() {
        for (bad in listOf(
            "(1, 0, 0)", "foo(0.1, 0.2, 0.3)", "x[0](0.1, 0.2, 0.3)", "[0.1, 0.2]", "[0.1, 0.2, 0.3, 0.4, 0.5]",
            "[0.1, 1.5, 0.3]", "(0.1, 0.2, 0.3]", "[-0.1, 0.2, 0.3]",
        )) {
            assertEquals(bad, emptyList<String>(), tuples(bad))
        }
    }

    @Test
    fun `parses tuple channels`() {
        assertEquals(Color(255, 128, 0), FloatTuples.parse("[1.0, 0.5, 0.0]")!!.first.toAwt())
        assertEquals(Color(255, 128, 0, 128), FloatTuples.parse("(1.0, 0.5, 0.0, 0.5)")!!.first.toAwt())
    }

    @Test
    fun `tuples keep their style, decimal points and gain alpha`() {
        assertEquals("[1.0, 0.0, 0.0]", pickTuple("[0.1, 0.2, 0.3]", Color(255, 0, 0)))
        assertEquals("(1.0,0.5,0.0)", pickTuple("(0.0,0.0,0.0)", Color(255, 128, 0)))
        assertEquals("[1.0, .5, 0]", pickTuple("[.1, .1, 0]", Color(255, 128, 0)))
        assertEquals("(0.07, 0.204, 0.337, 0.5)", pickTuple("(1.0, 0.5, 0.0)", Color(0x12, 0x34, 0x56, 0x80)))
        assertEquals("(1.0, 0.5, 0.0, 1.0)", pickTuple("(0.0, 0.0, 0.0, 0.5)", Color(255, 128, 0)))
    }

    @Test
    fun `written tuples give back the picked color`() {
        val written = pickTuple("(1.0, 0.5, 0.0)", Color(0x12, 0x34, 0x56, 0x80))
        assertEquals(Color(0x12, 0x34, 0x56, 0x80), FloatTuples.parse(written)!!.first.toAwt())
    }
}
