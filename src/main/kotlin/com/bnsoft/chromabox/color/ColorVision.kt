package com.bnsoft.chromabox.color

import java.awt.Color

/**
 * A color vision deficiency to simulate (C-05). The dichromacies use the Machado, Oliveira and Fernandes (2009)
 * matrices at severity 1.0, applied in linear sRGB; achromatopsia keeps only the relative luminance (C-05.3).
 */
enum class ColorVision(private val matrix: DoubleArray?) {
    NONE(null),
    PROTANOPIA(
        doubleArrayOf(
            0.152286, 1.052583, -0.204868,
            0.114503, 0.786281, 0.099216,
            -0.003882, -0.048116, 1.051998,
        ),
    ),
    DEUTERANOPIA(
        doubleArrayOf(
            0.367322, 0.860646, -0.227968,
            0.280085, 0.672501, 0.047413,
            -0.011820, 0.042940, 0.968881,
        ),
    ),
    TRITANOPIA(
        doubleArrayOf(
            1.255528, -0.076749, -0.178779,
            -0.078411, 0.930809, 0.147602,
            0.004733, 0.691367, 0.303900,
        ),
    ),
    ACHROMATOPSIA(null),
    ;

    /** How [color] looks with this deficiency, clamped to sRGB; alpha stays. */
    fun simulate(color: ChromaColor): ChromaColor {
        if (this == NONE) return color
        val r = ColorSpaces.srgbToLinear(color.r.coerceIn(0.0, 1.0))
        val g = ColorSpaces.srgbToLinear(color.g.coerceIn(0.0, 1.0))
        val b = ColorSpaces.srgbToLinear(color.b.coerceIn(0.0, 1.0))
        fun encode(linear: Double) = ColorSpaces.linearToSrgb(linear.coerceIn(0.0, 1.0))
        val m = matrix
        if (m == null) {
            val y = encode(0.2126 * r + 0.7152 * g + 0.0722 * b)
            return ChromaColor(y, y, y, color.alpha)
        }
        return ChromaColor(
            encode(m[0] * r + m[1] * g + m[2] * b),
            encode(m[3] * r + m[4] * g + m[5] * b),
            encode(m[6] * r + m[7] * g + m[8] * b),
            color.alpha,
        )
    }

    fun simulate(color: Color): Color = if (this == NONE) color else simulate(ChromaColor.fromAwt(color)).toAwt()
}
