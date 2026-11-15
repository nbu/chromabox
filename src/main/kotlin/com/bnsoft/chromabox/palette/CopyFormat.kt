package com.bnsoft.chromabox.palette

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.color.ColorSpaces
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Notations a palette color can be copied in (C-03.5), from the 8-bit sRGB color. */
enum class CopyFormat {
    HEX, RGB, HSL, OKLCH, COMPOSE, SWIFT;

    fun format(color: ChromaColor): String {
        val awt = color.toAwt()
        val alpha = awt.alpha / 255.0
        val opaque = awt.alpha == 255
        fun slashAlpha() = if (opaque) "" else " / " + number(alpha, 3)
        return when (this) {
            HEX -> Palette.hex(color)
            RGB -> "rgb(${awt.red} ${awt.green} ${awt.blue}${slashAlpha()})"
            HSL -> ColorSpaces.toHsl(ChromaColor.fromAwt(awt)).let {
                "hsl(${number(hue(it.h), 1)} ${number(it.s * 100, 1)}% ${number(it.l * 100, 1)}%${slashAlpha()})"
            }
            OKLCH -> ColorSpaces.toOklch(ChromaColor.fromAwt(awt)).let {
                "oklch(${number(it.l * 100, 2)}% ${number(it.c, 4)} ${number(hue(it.h), 2)}${slashAlpha()})"
            }
            COMPOSE -> String.format(Locale.ROOT, "Color(0x%02X%02X%02X%02X)", awt.alpha, awt.red, awt.green, awt.blue)
            SWIFT -> "Color(red: ${number(awt.red / 255.0, 3)}, green: ${number(awt.green / 255.0, 3)}, " +
                "blue: ${number(awt.blue / 255.0, 3)}, opacity: ${number(alpha, 3)})"
        }
    }

    private companion object {
        // An achromatic color has no hue; 0 is valid everywhere, unlike CSS's "none".
        fun hue(h: Double) = if (h.isNaN()) 0.0 else h

        /** At most [decimals] decimals, without trailing zeros: 0.5, 1, 62.79. */
        fun number(value: Double, decimals: Int): String =
            BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
