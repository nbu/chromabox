package com.bnsoft.chromabox.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

class ColorSpacesTest {
    private val samples = listOf(
        ChromaColor(0.0, 0.0, 0.0),
        ChromaColor(1.0, 1.0, 1.0),
        ChromaColor(0.5, 0.5, 0.5, 0.3),
        ChromaColor(1.0, 0.0, 0.0),
        ChromaColor(0.0, 1.0, 0.0),
        ChromaColor(0.0, 0.0, 1.0),
        ChromaColor(1.0, 1.0, 0.0),
        ChromaColor.fromRgb255(0x1E, 0x90, 0xFF),
        ChromaColor.fromRgb255(0x8B, 0x45, 0x13, 0.5),
        ChromaColor(0.2, 0.7, 0.4),
        ChromaColor(0.01, 0.02, 0.03),
    )

    private fun assertColor(expected: ChromaColor, actual: ChromaColor, tolerance: Double = 1e-6) {
        val message = "expected $expected but was $actual"
        assertEquals(message, expected.r, actual.r, tolerance)
        assertEquals(message, expected.g, actual.g, tolerance)
        assertEquals(message, expected.b, actual.b, tolerance)
        assertEquals(message, expected.alpha, actual.alpha, 0.0)
    }

    private fun roundTrip(convert: (ChromaColor) -> ChromaColor) = samples.forEach { assertColor(it, convert(it)) }

    @Test
    fun `round trips through every space`() {
        roundTrip { ColorSpaces.fromHsl(ColorSpaces.toHsl(it), it.alpha) }
        roundTrip { ColorSpaces.fromHwb(ColorSpaces.toHwb(it), it.alpha) }
        roundTrip { ColorSpaces.fromLab(ColorSpaces.toLab(it), it.alpha) }
        roundTrip { ColorSpaces.fromLch(ColorSpaces.toLch(it), it.alpha) }
        roundTrip { ColorSpaces.fromOklab(ColorSpaces.toOklab(it), it.alpha) }
        roundTrip { ColorSpaces.fromOklch(ColorSpaces.toOklch(it), it.alpha) }
        roundTrip { ColorSpaces.fromDisplayP3(ColorSpaces.toDisplayP3(it), it.alpha) }
    }

    @Test
    fun `round trips out-of-gamut colors`() {
        val wide = ColorSpaces.fromDisplayP3(Rgb(1.0, 0.0, 0.0))
        assertColor(wide, ColorSpaces.fromOklch(ColorSpaces.toOklch(wide)))
        assertColor(wide, ColorSpaces.fromLab(ColorSpaces.toLab(wide)))
        val p3 = ColorSpaces.toDisplayP3(wide)
        assertEquals(1.0, p3.r, 1e-6)
        assertEquals(0.0, p3.g, 1e-6)
        assertEquals(0.0, p3.b, 1e-6)
    }

    @Test
    fun `srgb transfer round trips and is linear near zero`() {
        // The standard's two segments meet with a ~3e-8 seam at 0.04045.
        for (v in listOf(-0.5, 0.0, 0.002, 0.04045, 0.2, 0.5, 1.0, 1.2)) {
            assertEquals(v, ColorSpaces.linearToSrgb(ColorSpaces.srgbToLinear(v)), 1e-7)
        }
        assertEquals(0.21404114, ColorSpaces.srgbToLinear(0.5), 1e-8)
        assertEquals(-ColorSpaces.srgbToLinear(0.5), ColorSpaces.srgbToLinear(-0.5), 0.0)
    }

    @Test
    fun `red in oklch`() {
        val lch = ColorSpaces.toOklch(ChromaColor(1.0, 0.0, 0.0))
        assertEquals(0.62796, lch.l, 1e-4)
        assertEquals(0.25768, lch.c, 1e-4)
        assertEquals(29.2339, lch.h, 1e-3)
    }

    @Test
    fun `white and black in lab`() {
        val white = ColorSpaces.toLab(ChromaColor(1.0, 1.0, 1.0))
        assertEquals(100.0, white.l, 1e-4)
        assertEquals(0.0, white.a, 1e-4)
        assertEquals(0.0, white.b, 1e-4)
        assertEquals(0.0, ColorSpaces.toLab(ChromaColor(0.0, 0.0, 0.0)).l, 1e-9)
    }

    @Test
    fun `red in lab`() {
        val lab = ColorSpaces.toLab(ChromaColor(1.0, 0.0, 0.0))
        assertEquals(54.29, lab.l, 0.01)
        assertEquals(80.80, lab.a, 0.01)
        assertEquals(69.89, lab.b, 0.01)
    }

    @Test
    fun `hsl reference value`() {
        val color = ColorSpaces.fromHsl(Hsl(120.0, 0.5, 0.5)).toAwt()
        assertEquals(Color(64, 191, 64), color)
        assertEquals(Hsl(210.0, 1.0, 0.5), ColorSpaces.toHsl(ChromaColor(0.0, 0.5, 1.0)))
    }

    @Test
    fun `hwb reference value`() {
        assertColor(ChromaColor(0.6, 0.2, 0.2), ColorSpaces.fromHwb(Hwb(0.0, 0.2, 0.4)), 1e-12)
        assertColor(ChromaColor(0.6, 0.6, 0.6), ColorSpaces.fromHwb(Hwb(90.0, 0.6, 0.4)), 1e-12)
        assertColor(ChromaColor(0.5, 0.5, 0.5), ColorSpaces.fromHwb(Hwb(90.0, 0.8, 0.8)), 1e-12)
    }

    @Test
    fun `achromatic colors have NaN hue`() {
        val gray = ChromaColor(0.5, 0.5, 0.5)
        for (c in listOf(gray, ChromaColor(1.0, 1.0, 1.0), ChromaColor(0.0, 0.0, 0.0))) {
            assertTrue(ColorSpaces.toHsl(c).h.isNaN())
            assertTrue(ColorSpaces.toHwb(c).h.isNaN())
            assertTrue(ColorSpaces.toLch(c).h.isNaN())
            assertTrue(ColorSpaces.toOklch(c).h.isNaN())
        }
        assertFalse(ColorSpaces.toOklch(ChromaColor(0.5, 0.5, 0.51)).h.isNaN())
        assertColor(gray, ColorSpaces.fromOklch(ColorSpaces.toOklch(gray)))
    }

    @Test
    fun `wide colors are out of srgb gamut`() {
        assertFalse(ColorSpaces.fromDisplayP3(Rgb(1.0, 0.0, 0.0)).inGamut)
        assertFalse(ColorSpaces.fromOklch(Oklch(0.7, 0.4, 200.0)).inGamut)
        assertTrue(ColorSpaces.fromDisplayP3(ColorSpaces.toDisplayP3(ChromaColor(0.3, 0.6, 0.9))).inGamut)
        assertTrue(ColorSpaces.fromOklch(ColorSpaces.toOklch(ChromaColor(1.0, 1.0, 1.0))).inGamut)
    }

    @Test
    fun `in gamut tolerates tiny overshoot`() {
        assertTrue(ChromaColor(1.00005, -0.00005, 0.5).inGamut)
        assertFalse(ChromaColor(1.001, 0.0, 0.5).inGamut)
        assertFalse(ChromaColor(0.5, -0.001, 0.5).inGamut)
    }

    @Test
    fun `toAwt clamps and rounds`() {
        assertEquals(Color(255, 0, 128, 255), ChromaColor(1.3, -0.2, 0.5).toAwt())
        assertEquals(Color(0, 0, 0, 128), ChromaColor(0.0, 0.0, 0.0, 0.5).toAwt())
    }

    @Test
    fun `awt round trip`() {
        val awt = Color(0x1E, 0x90, 0xFF, 0x80)
        val color = ChromaColor.fromAwt(awt)
        assertEquals(0x80 / 255.0, color.alpha, 0.0)
        assertEquals(awt, color.toAwt())
        assertEquals(ChromaColor(1.0, 0.0, 51 / 255.0, 0.5), ChromaColor.fromRgb255(255, 0, 51, 0.5))
    }
}
