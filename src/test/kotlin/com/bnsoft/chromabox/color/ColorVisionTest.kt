package com.bnsoft.chromabox.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorVisionTest {
    private fun rgb(r: Int, g: Int, b: Int) = ChromaColor.fromRgb255(r, g, b)

    private fun ColorVision.of(r: Int, g: Int, b: Int) = simulate(rgb(r, g, b)).toAwt().let { Triple(it.red, it.green, it.blue) }

    @Test
    fun `primaries match the Machado 2009 matrices at severity 1, in linear sRGB`() {
        assertEquals(Triple(109, 95, 0), ColorVision.PROTANOPIA.of(255, 0, 0))
        assertEquals(Triple(255, 229, 0), ColorVision.PROTANOPIA.of(0, 255, 0))
        assertEquals(Triple(0, 89, 255), ColorVision.PROTANOPIA.of(0, 0, 255))
        assertEquals(Triple(163, 144, 0), ColorVision.DEUTERANOPIA.of(255, 0, 0))
        assertEquals(Triple(239, 214, 58), ColorVision.DEUTERANOPIA.of(0, 255, 0))
        assertEquals(Triple(0, 61, 251), ColorVision.DEUTERANOPIA.of(0, 0, 255))
        assertEquals(Triple(255, 0, 15), ColorVision.TRITANOPIA.of(255, 0, 0))
        assertEquals(Triple(0, 247, 217), ColorVision.TRITANOPIA.of(0, 255, 0))
        assertEquals(Triple(0, 107, 150), ColorVision.TRITANOPIA.of(0, 0, 255))
    }

    @Test
    fun `grays stay, alpha stays, and none changes nothing`() {
        for (vision in ColorVision.entries) {
            for (v in listOf(0, 51, 128, 200, 255)) assertEquals(vision.name, Triple(v, v, v), vision.of(v, v, v))
            assertEquals(0.4, vision.simulate(ChromaColor(1.0, 0.0, 0.0, 0.4)).alpha, 0.0)
        }
        val color = ChromaColor(0.2, 0.5, 0.9)
        assertSame(color, ColorVision.NONE.simulate(color))
    }

    @Test
    fun `achromatopsia keeps relative luminance`() {
        for (color in listOf(rgb(255, 0, 0), rgb(0, 128, 0), rgb(30, 144, 255), rgb(250, 200, 10))) {
            val gray = ColorVision.ACHROMATOPSIA.simulate(color)
            assertEquals(gray.r, gray.g, 0.0)
            assertEquals(gray.r, gray.b, 0.0)
            assertEquals(Contrast.luminance(color), Contrast.luminance(gray), 1e-9)
        }
    }

    @Test
    fun `contrast of the simulated colors`() {
        val red = rgb(255, 0, 0)
        val black = rgb(0, 0, 0)
        assertEquals("5.2:1", Contrast.of(red, black).ratioText)
        assertEquals("3.2:1", Contrast.of(red, black, ColorVision.PROTANOPIA).ratioText)
        // Blended first, then simulated: a translucent foreground is seen mixed with its background.
        val halfRed = ChromaColor(1.0, 0.0, 0.0, 0.5)
        val blended = Contrast.composite(halfRed, black)
        assertEquals(Contrast.of(blended, black, ColorVision.PROTANOPIA).ratio, Contrast.of(halfRed, black, ColorVision.PROTANOPIA).ratio, 1e-12)
    }

    @Test
    fun `a fix under a simulation reaches the target with and without it`() {
        val red = rgb(255, 0, 0)
        val black = rgb(0, 0, 0)
        // Red on black passes AA as it is, but not for someone with protanopia.
        assertNull(ContrastFix.suggest(red, black, ContrastResult.AA))
        val fixed = ContrastFix.suggest(red, black, ContrastResult.AA, ColorVision.PROTANOPIA)
        assertNotNull(fixed)
        fixed!!
        assertTrue(Contrast.of(fixed, black).ratio >= ContrastResult.AA)
        assertTrue(Contrast.of(fixed, black, ColorVision.PROTANOPIA).ratio >= ContrastResult.AA)
    }
}
