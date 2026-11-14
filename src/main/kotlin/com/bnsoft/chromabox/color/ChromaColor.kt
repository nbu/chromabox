package com.bnsoft.chromabox.color

import java.awt.Color
import kotlin.math.roundToInt

/**
 * A color as gamma-encoded sRGB channels. Channels are nominally 0..1 but not clamped, so colors from
 * wider spaces (oklch, display-p3, …) keep their out-of-gamut values; [alpha] is 0..1.
 */
data class ChromaColor(val r: Double, val g: Double, val b: Double, val alpha: Double = 1.0) {
    val inGamut: Boolean
        get() = listOf(r, g, b).all { it >= -GAMUT_EPSILON && it <= 1 + GAMUT_EPSILON }

    /** Clamps to the sRGB gamut and rounds to 8 bits per channel. */
    fun toAwt(): Color = Color(to8Bit(r), to8Bit(g), to8Bit(b), to8Bit(alpha))

    companion object {
        const val GAMUT_EPSILON = 1e-4

        fun fromAwt(color: Color) = fromRgb255(color.red, color.green, color.blue, color.alpha / 255.0)

        fun fromRgb255(r: Int, g: Int, b: Int, alpha: Double = 1.0) = ChromaColor(r / 255.0, g / 255.0, b / 255.0, alpha)

        private fun to8Bit(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
    }
}
