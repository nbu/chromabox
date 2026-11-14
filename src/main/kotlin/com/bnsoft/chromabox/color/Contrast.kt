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

    /** [foreground] composited over [background]; a translucent background is composited over white first. */
    fun of(foreground: ChromaColor, background: ChromaColor): ContrastResult {
        val translucent = background.alpha < 1
        val back = composite(background, WHITE)
        return ContrastResult(ratio(composite(foreground, back), back), translucent)
    }
}
