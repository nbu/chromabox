package com.bnsoft.chromabox.color

import org.junit.Assert.assertEquals
import org.junit.Test

class ContrastTest {
    private fun hex(rgb: Int, alpha: Double = 1.0) = ChromaColor.fromRgb255(rgb shr 16 and 0xff, rgb shr 8 and 0xff, rgb and 0xff, alpha)

    private val white = hex(0xffffff)
    private val black = hex(0x000000)

    @Test
    fun `luminance and ratio match WCAG reference values`() {
        assertEquals(1.0, Contrast.luminance(white), 1e-9)
        assertEquals(0.0, Contrast.luminance(black), 1e-9)
        assertEquals(21.0, Contrast.ratio(black, white), 1e-9)
        assertEquals(1.0, Contrast.ratio(white, white), 1e-9)
        // Commonly cited: #767676 is the lightest grey that passes AA on white, #777777 just fails.
        assertEquals(4.54, Contrast.ratio(hex(0x767676), white), 0.005)
        assertEquals(4.48, Contrast.ratio(hex(0x777777), white), 0.005)
        assertEquals(Contrast.ratio(hex(0x1e90ff), black), Contrast.ratio(black, hex(0x1e90ff)), 1e-12)
    }

    @Test
    fun `levels use WCAG 2_2 thresholds and the text never rounds up`() {
        val aa = Contrast.of(hex(0x767676), white)
        assertEquals(ContrastLevel.AA, aa.level)
        assertEquals("4.5:1", aa.ratioText)
        val large = Contrast.of(hex(0x777777), white)
        assertEquals(ContrastLevel.AA_LARGE, large.level)
        assertEquals("4.4:1", large.ratioText)
        assertEquals("21:1", Contrast.of(black, white).ratioText)
        assertEquals(ContrastLevel.AAA, Contrast.of(black, white).level)
        assertEquals(ContrastLevel.FAIL, Contrast.of(hex(0xcccccc), white).level)
    }

    @Test
    fun `a translucent foreground is composited over the background`() {
        val half = Contrast.of(hex(0x000000, 0.5), white)
        assertEquals(Contrast.ratio(ChromaColor(0.5, 0.5, 0.5), white), half.ratio, 1e-9)
        assertEquals(false, half.approximate)
    }

    @Test
    fun `a translucent background is composited over white and marked approximate`() {
        val result = Contrast.of(white, hex(0x000000, 0.5))
        assertEquals(true, result.approximate)
        assertEquals(Contrast.ratio(white, ChromaColor(0.5, 0.5, 0.5)), result.ratio, 1e-9)
        assertEquals("~" + Contrast.of(white, ChromaColor(0.5, 0.5, 0.5)).ratioText, result.ratioText)
    }
}
