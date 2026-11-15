package com.bnsoft.chromabox.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastFixTest {
    private fun hex(rgb: Int, alpha: Double = 1.0) = ChromaColor.fromRgb255(rgb shr 16 and 0xff, rgb shr 8 and 0xff, rgb and 0xff, alpha)

    private val white = hex(0xffffff)
    private val black = hex(0x000000)

    @Test
    fun `APCA matches reference values`() {
        // From the APCA reference implementation: black on white and white on black.
        assertEquals(106.04, Apca.lc(black, white), 0.01)
        assertEquals(-107.88, Apca.lc(white, black), 0.01)
        assertEquals(0.0, Apca.lc(white, white), 0.0)
    }

    @Test
    fun `the fix is the nearest lightness that reaches the target`() {
        val fixed = ContrastFix.suggest(hex(0x777777), white, ContrastResult.AA)!!
        assertEquals(hex(0x767676), fixed)
        assertTrue(Contrast.of(fixed, white).ratio >= ContrastResult.AA)
    }

    @Test
    fun `the fix keeps hue and chroma roughly, and alpha exactly`() {
        val blue = hex(0x6699ff)
        val fixed = ContrastFix.suggest(blue, white, ContrastResult.AAA)!!
        assertTrue(Contrast.of(fixed, white).ratio >= ContrastResult.AAA)
        val before = ColorSpaces.toOklch(blue)
        val after = ColorSpaces.toOklch(fixed)
        assertTrue(after.l < before.l)
        assertEquals(before.h, after.h, 3.0)

        val translucent = ContrastFix.suggest(hex(0x777777, 0.8), white, ContrastResult.AA)!!
        assertEquals(hex(0x777777, 0.8).toAwt().alpha, translucent.toAwt().alpha)
        assertTrue(Contrast.of(translucent, white).ratio >= ContrastResult.AA)
    }

    @Test
    fun `lighter if that's nearer, and nothing if it already passes or can't`() {
        val dark = hex(0x101010)
        val fixed = ContrastFix.suggest(hex(0x404040), dark, ContrastResult.AA)!!
        assertTrue(ColorSpaces.toOklch(fixed).l > ColorSpaces.toOklch(hex(0x404040)).l)
        assertNull(ContrastFix.suggest(black, white, ContrastResult.AAA))
        // Against mid grey no lightness reaches 7:1.
        assertNull(ContrastFix.suggest(hex(0x808080), hex(0x777777), ContrastResult.AAA))
    }
}
