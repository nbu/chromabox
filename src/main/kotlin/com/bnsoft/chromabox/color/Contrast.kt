package com.bnsoft.chromabox.color

import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** WCAG 2.2 outcomes for normal text: under 3 fails; 3 passes AA for large text only; 4.5 is AA; 7 is AAA. */
enum class ContrastLevel { FAIL, AA_LARGE, AA, AAA }

/**
 * The contrast of a foreground over a background (C-02). [approximate] means the background is translucent, so the
 * ratio was computed over white and depends on what's behind it (C-02.5).
 */
data class ContrastResult(val ratio: Double, val approximate: Boolean) {
    val level: ContrastLevel
        get() = when {
            ratio >= AAA -> ContrastLevel.AAA
            ratio >= AA -> ContrastLevel.AA
            ratio >= AA_LARGE -> ContrastLevel.AA_LARGE
            else -> ContrastLevel.FAIL
        }

    /**
     * `4.6:1`, truncated to one decimal rather than rounded, so a ratio shown as 4.5 always passes 4.5; `~` first
     * if [approximate].
     */
    val ratioText: String
        get() {
            val tenths = floor(ratio * 10 + 1e-9) / 10
            val number = if (tenths % 1.0 == 0.0) tenths.toInt().toString() else String.format(Locale.ROOT, "%.1f", tenths)
            return (if (approximate) "~" else "") + number + ":1"
        }

    companion object {
        // WCAG 2.2 thresholds: AA normal text, AA large text and UI components, AAA normal text (AAA large is 4.5).
        const val AA = 4.5
        const val AA_LARGE = 3.0
        const val AAA = 7.0
    }
}

object Contrast {
    private val WHITE = ChromaColor(1.0, 1.0, 1.0)

    /** WCAG relative luminance of the clamped sRGB color, ignoring alpha. */
    fun luminance(color: ChromaColor): Double {
        fun linear(c: Double) = ColorSpaces.srgbToLinear(c.coerceIn(0.0, 1.0))
        return 0.2126 * linear(color.r) + 0.7152 * linear(color.g) + 0.0722 * linear(color.b)
    }

    /** WCAG contrast ratio of two opaque colors, 1 to 21. */
    fun ratio(a: ChromaColor, b: ChromaColor): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [front] over the opaque [back], blended in gamma-encoded sRGB like browsers do. */
    fun composite(front: ChromaColor, back: ChromaColor): ChromaColor {
        val a = front.alpha.coerceIn(0.0, 1.0)
        fun mix(f: Double, b: Double) = f.coerceIn(0.0, 1.0) * a + b.coerceIn(0.0, 1.0) * (1 - a)
        return ChromaColor(mix(front.r, back.r), mix(front.g, back.g), mix(front.b, back.b))
    }

    /**
     * [foreground] composited over [background]; a translucent background is composited over white first. With
     * [vision], the ratio of the blended colors as they look with that deficiency (C-05.4).
     */
    fun of(foreground: ChromaColor, background: ChromaColor, vision: ColorVision = ColorVision.NONE): ContrastResult {
        val translucent = background.alpha < 1
        val back = composite(background, WHITE)
        val front = composite(foreground, back)
        return ContrastResult(ratio(vision.simulate(front), vision.simulate(back)), translucent)
    }
}

/**
 * APCA (C-02.6), version 0.0.98G-4g: the lightness contrast Lc of [text] on the opaque [background], about -108 to
 * 106. Positive for dark text on a light background, negative for light text on a dark one.
 */
object Apca {
    fun lc(text: ChromaColor, background: ChromaColor): Double {
        val yText = clampBlack(y(text))
        val yBack = clampBlack(y(background))
        if (kotlin.math.abs(yBack - yText) < 0.0005) return 0.0
        val sapc = if (yBack > yText) {
            (Math.pow(yBack, 0.56) - Math.pow(yText, 0.57)) * 1.14
        } else {
            (Math.pow(yBack, 0.65) - Math.pow(yText, 0.62)) * 1.14
        }
        val lc = when {
            yBack > yText -> if (sapc < 0.1) 0.0 else sapc - 0.027
            else -> if (sapc > -0.1) 0.0 else sapc + 0.027
        }
        return lc * 100
    }

    // APCA linearizes with a plain 2.4 power and its own coefficients, unlike WCAG.
    private fun y(color: ChromaColor): Double {
        fun c(v: Double) = Math.pow(v.coerceIn(0.0, 1.0), 2.4)
        return 0.2126729 * c(color.r) + 0.7151522 * c(color.g) + 0.0721750 * c(color.b)
    }

    private fun clampBlack(y: Double) = if (y < 0.022) y + Math.pow(0.022 - y, 1.414) else y
}

/** Suggests colors that reach a contrast ratio (C-02.4). */
object ContrastFix {
    private const val STEP = 0.0025

    /**
     * The color nearest to [foreground] that reaches [target] against [background], changing only its OKLCH lightness,
     * so hue, chroma and alpha stay. Rounded to 8 bits per channel, so the written color reaches [target] too. With
     * [vision], it must reach [target] both as it is and as it looks with that deficiency. Null if [foreground]
     * already reaches it, or no lightness does.
     */
    fun suggest(foreground: ChromaColor, background: ChromaColor, target: Double, vision: ColorVision = ColorVision.NONE): ChromaColor? {
        fun reaches(color: ChromaColor) =
            Contrast.of(color, background).ratio >= target && Contrast.of(color, background, vision).ratio >= target
        if (reaches(foreground)) return null
        val oklch = ColorSpaces.toOklch(foreground)
        fun at(l: Double): ChromaColor? {
            if (l < 0 || l > 1) return null
            val color = ChromaColor.fromAwt(ColorSpaces.fromOklch(Oklch(l, oklch.c, oklch.h), foreground.alpha).toAwt())
            return color.takeIf(::reaches)
        }
        // Nearest first: one step lighter, one step darker, two steps lighter, and so on.
        for (step in 1..(1 / STEP).toInt()) {
            (at(oklch.l + step * STEP) ?: at(oklch.l - step * STEP))?.let { return it }
        }
        return null
    }
}
