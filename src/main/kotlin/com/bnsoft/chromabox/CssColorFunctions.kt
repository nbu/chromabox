package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.color.ColorSpaces
import com.bnsoft.chromabox.color.Hsl
import com.bnsoft.chromabox.color.Hwb
import com.bnsoft.chromabox.color.Lab
import com.bnsoft.chromabox.color.Lch
import com.bnsoft.chromabox.color.Oklab
import com.bnsoft.chromabox.color.Oklch
import com.bnsoft.chromabox.color.Rgb
import com.intellij.openapi.util.TextRange
import java.math.BigDecimal
import java.math.RoundingMode
import java.awt.Color
import kotlin.math.abs
import kotlin.math.roundToInt

/** One number inside a CSS color function, with the details needed to write a new value the same way. */
data class CssNumber(
    val range: TextRange,
    val value: Double,
    val unit: String,
    val decimals: Int,
    val leadingZero: Boolean,
    /** True if the fraction ends in zero, like `0.50`; then new values keep [decimals] digits. */
    val trailingZeros: Boolean = false,
)

/**
 * One component of a CSS color function. Its value is held as a plain number: degrees for a hue, otherwise the
 * number as written, with `100%` meaning [percentScale]. Plain numbers get at most [numberDecimals] decimals.
 */
class CssComponent(val percentScale: Double, val numberDecimals: Int, val isHue: Boolean = false) {
    companion object {
        val CHANNEL = CssComponent(255.0, 0)
        val PERCENT = CssComponent(100.0, 2)
        val HUE = CssComponent(0.0, 1, isHue = true)
        val UNIT = CssComponent(1.0, 2)
    }
}

/** A CSS color function and how its three components map to a color. */
enum class CssColorFunction(
    val names: Set<String>,
    val components: List<CssComponent>,
    val legacySyntax: Boolean = false,
    /** For `color()`: the predefined color space, the first argument. */
    val colorSpace: String? = null,
) {
    RGB(setOf("rgb", "rgba"), List(3) { CssComponent.CHANNEL }, legacySyntax = true) {
        override fun toColor(v: List<Double>, alpha: Double) = ChromaColor(v[0] / 255, v[1] / 255, v[2] / 255, alpha)
        override fun fromColor(color: ChromaColor) = listOf(color.r * 255, color.g * 255, color.b * 255)
    },
    HSL(setOf("hsl", "hsla"), listOf(CssComponent.HUE, CssComponent.PERCENT, CssComponent.PERCENT), legacySyntax = true) {
        override fun toColor(v: List<Double>, alpha: Double) =
            ColorSpaces.fromHsl(Hsl(v[0], percent(v[1]), percent(v[2])), alpha)

        override fun fromColor(color: ChromaColor) = ColorSpaces.toHsl(color).let { listOf(it.h, it.s * 100, it.l * 100) }
    },
    HWB(setOf("hwb"), listOf(CssComponent.HUE, CssComponent.PERCENT, CssComponent.PERCENT)) {
        override fun toColor(v: List<Double>, alpha: Double) =
            ColorSpaces.fromHwb(Hwb(v[0], percent(v[1]), percent(v[2])), alpha)

        override fun fromColor(color: ChromaColor) = ColorSpaces.toHwb(color).let { listOf(it.h, it.w * 100, it.b * 100) }
    },
    LAB(setOf("lab"), listOf(CssComponent(100.0, 2), CssComponent(125.0, 2), CssComponent(125.0, 2))) {
        override fun toColor(v: List<Double>, alpha: Double) = ColorSpaces.fromLab(Lab(v[0].coerceIn(0.0, 100.0), v[1], v[2]), alpha)
        override fun fromColor(color: ChromaColor) = ColorSpaces.toLab(color).let { listOf(it.l, it.a, it.b) }
    },
    LCH(setOf("lch"), listOf(CssComponent(100.0, 2), CssComponent(150.0, 2), CssComponent.HUE)) {
        override fun toColor(v: List<Double>, alpha: Double) =
            ColorSpaces.fromLch(Lch(v[0].coerceIn(0.0, 100.0), v[1].coerceAtLeast(0.0), v[2]), alpha)

        override fun fromColor(color: ChromaColor) = ColorSpaces.toLch(color).let { listOf(it.l, it.c, it.h) }
    },
    OKLAB(setOf("oklab"), listOf(CssComponent(1.0, 3), CssComponent(0.4, 3), CssComponent(0.4, 3))) {
        override fun toColor(v: List<Double>, alpha: Double) = ColorSpaces.fromOklab(Oklab(v[0].coerceIn(0.0, 1.0), v[1], v[2]), alpha)
        override fun fromColor(color: ChromaColor) = ColorSpaces.toOklab(color).let { listOf(it.l, it.a, it.b) }
    },
    OKLCH(setOf("oklch"), listOf(CssComponent(1.0, 3), CssComponent(0.4, 3), CssComponent.HUE)) {
        override fun toColor(v: List<Double>, alpha: Double) =
            ColorSpaces.fromOklch(Oklch(v[0].coerceIn(0.0, 1.0), v[1].coerceAtLeast(0.0), v[2]), alpha)

        override fun fromColor(color: ChromaColor) = ColorSpaces.toOklch(color).let { listOf(it.l, it.c, it.h) }
    },
    DISPLAY_P3(setOf("color"), List(3) { CssComponent.UNIT }, colorSpace = "display-p3") {
        override fun toColor(v: List<Double>, alpha: Double) = ColorSpaces.fromDisplayP3(Rgb(v[0], v[1], v[2]), alpha)
        override fun fromColor(color: ChromaColor) = ColorSpaces.toDisplayP3(color).let { listOf(it.r, it.g, it.b) }
    },
    SRGB(setOf("color"), List(3) { CssComponent.UNIT }, colorSpace = "srgb") {
        override fun toColor(v: List<Double>, alpha: Double) = ChromaColor(v[0], v[1], v[2], alpha)
        override fun fromColor(color: ChromaColor) = listOf(color.r, color.g, color.b)
    },
    ;

    /** [v] holds the component values as described in [CssComponent]. */
    abstract fun toColor(v: List<Double>, alpha: Double): ChromaColor

    /** The inverse of [toColor], without alpha; a hue is NaN for achromatic colors. */
    abstract fun fromColor(color: ChromaColor): List<Double>

    companion object {
        /** The functions from CSS Color 3 (`rgb()`, `hsl()`, `hwb()`); the rest are CSS Color 4. */
        val LEVEL_3: Set<CssColorFunction> = setOf(RGB, HSL, HWB)
        val LEVEL_4: Set<CssColorFunction> = entries.toSet() - LEVEL_3

        private fun percent(v: Double) = (v / 100).coerceIn(0.0, 1.0)
    }
}

/**
 * How a CSS color function was written: the whole [literal], its three components and alpha. [separator] is the
 * text between the last two components, reused when alpha is added.
 */
data class CssFunctionFormat(
    val literal: String,
    val function: CssColorFunction,
    val nameLength: Int,
    val components: List<CssNumber>,
    val alpha: CssNumber?,
    val legacy: Boolean,
    val separator: String,
)

data class CssColorMatch(val range: TextRange, val color: ChromaColor, val format: CssFunctionFormat)

/**
 * CSS color functions: `rgb()`, `rgba()`, `hsl()`, `hsla()`, `hwb()` in legacy (comma) and modern (space and slash)
 * syntax, and `lab()`, `lch()`, `oklab()`, `oklch()`, `color(display-p3 …)`, `color(srgb …)` in modern syntax.
 */
object CssColorFunctions {
    private val FUNCTION = Regex("""(?<![\w-])(rgba?|hsla?|hwb|lab|lch|oklab|oklch|color)\([^()]*\)""", RegexOption.IGNORE_CASE)
    private val COLOR_SPACE = Regex("""\s*([a-zA-Z][\w-]*)\s+""")
    private val NUMBER = Regex("""[+-]?(?:\d+(?:\.(\d*))?|\.(\d+))(?:[eE][+-]?\d+)?(%|deg|grad|rad|turn)?""", RegexOption.IGNORE_CASE)
    private val SEPARATOR = Regex("""\s*([,/])?\s*""")
    private val HUE_UNITS = setOf("", "deg", "grad", "rad", "turn")

    // Most decimals written for percentages and alpha (C-01.d).
    private const val PERCENT_DECIMALS = 2
    private const val ALPHA_DECIMALS = 2

    // Most decimals written when the limits above can't give the picked color exactly.
    private const val EXACT_DECIMALS = 6
    private const val MAX_OVERSHOOT = 2.0

    fun findAll(text: CharSequence, functions: Set<CssColorFunction> = CssColorFunction.entries.toSet()): List<CssColorMatch> =
        FUNCTION.findAll(text).mapNotNull { m ->
            val (color, format) = parse(m.value) ?: return@mapNotNull null
            if (format.function !in functions) return@mapNotNull null
            CssColorMatch(TextRange(m.range.first, m.range.last + 1), color, format)
        }.toList()

    fun parse(literal: String): Pair<ChromaColor, CssFunctionFormat>? {
        val open = literal.indexOf('(')
        if (open < 0 || !literal.endsWith(')')) return null
        val name = literal.substring(0, open).lowercase()
        var start = open + 1
        val function = if (name == "color") {
            val space = COLOR_SPACE.matchAt(literal, start) ?: return null
            start = space.range.last + 1
            CssColorFunction.entries.find { it.colorSpace == space.groupValues[1].lowercase() } ?: return null
        } else {
            CssColorFunction.entries.find { name in it.names } ?: return null
        }

        val numbers = ArrayList<CssNumber>()
        val separators = ArrayList<String>()
        var pos = SEPARATOR.matchAt(literal, start)!!.range.last + 1
        val end = literal.length - 1
        while (pos < end) {
            val number = NUMBER.matchAt(literal, pos) ?: return null
            numbers += number.toCssNumber()
            pos = number.range.last + 1
            if (pos == end) break
            val separator = SEPARATOR.matchAt(literal, pos)!!
            if (separator.value.isEmpty()) return null
            separators += separator.value
            pos = separator.range.last + 1
        }
        if (numbers.isEmpty()) return null
        // A separator right before ')' must be whitespace only.
        if (separators.size >= numbers.size && separators.lastOrNull()?.isBlank() == false) return null
        if (separators.size >= numbers.size) separators.removeAt(separators.lastIndex)

        val kinds = separators.map { SEPARATOR.matchEntire(it)!!.groupValues[1] }
        val legacy = kinds.isNotEmpty() && kinds.all { it == "," }
        val modern = kinds == listOf("", "") || kinds == listOf("", "", "/")
        if (!(legacy && numbers.size in 3..4 && function.legacySyntax) && !modern) return null

        val components = numbers.take(3)
        val alpha = numbers.getOrNull(3)
        if (alpha != null && alpha.unit !in setOf("", "%")) return null
        val alphaValue = alpha?.let { (if (it.unit == "%") it.value / 100 else it.value).coerceIn(0.0, 1.0) } ?: 1.0

        val values = components.zip(function.components) { number, component ->
            val unit = number.unit.lowercase()
            when {
                component.isHue -> if (unit in HUE_UNITS) toDegrees(number.value, unit) else return null
                unit == "%" -> number.value / 100 * component.percentScale
                unit.isEmpty() -> number.value
                else -> return null
            }
        }
        val format = CssFunctionFormat(literal, function, open, components, alpha, legacy, separators.last())
        return function.toColor(values, alphaValue) to format
    }

    /**
     * Writes [color] the way [original] was written: same function name, separators, units, number style and
     * whitespace. Adds alpha in the style of the same syntax if [color] is translucent and [original] has none.
     * Uses the fewest decimals that still give the same 8-bit color, within the limits of C-01.d; only if no number
     * within the limits gives it, uses more.
     */
    fun format(color: ChromaColor, original: CssFunctionFormat): String {
        val target = color.toAwt()
        val values = original.function.fromColor(color)
        val alpha = if (original.alpha != null || target.alpha < 255) writeAlpha(color.alpha, target.alpha, original) else null
        val candidates = (0..EXACT_DECIMALS).asSequence().map { write(values, alpha, original, it) }
        return candidates.firstOrNull { gives(it, target) } ?: candidates.last()
    }

    /**
     * Alpha as written in [original], or in the default style of its syntax if it has none: `0.5` for legacy, `50%`
     * for modern. Independent of the other components, so it gets its own fewest decimals.
     */
    private fun writeAlpha(alpha: Double, target: Int, original: CssFunctionFormat): String {
        val style = original.alpha
            ?: if (original.legacy) CssNumber(TextRange.EMPTY_RANGE, alpha, "", 0, leadingZero = true)
            else CssNumber(TextRange.EMPTY_RANGE, alpha * 100, "%", 0, leadingZero = true)
        val percent = style.unit == "%"
        val value = if (percent) alpha * 100 else alpha
        val candidates = (0..EXACT_DECIMALS).asSequence().map { render(value, style, maxOf(ALPHA_DECIMALS, it), it) }
        return candidates.firstOrNull {
            val written = it.removeSuffix(style.unit).toDouble().let { v -> if (percent) v / 100 else v }
            (written.coerceIn(0.0, 1.0) * 255).roundToInt() == target
        } ?: candidates.last()
    }

    /**
     * True if [literal] gives [target], and does so without relying on clamping: every unclamped channel is within
     * [MAX_OVERSHOOT] 8-bit steps of the target. Rounded to the limits of C-01.d, a color at the edge of sRGB may
     * overshoot a little (sRGB red in oklch: about 1.2 steps); one that only matches after clamping lies far outside.
     */
    private fun gives(literal: String, target: Color): Boolean {
        val color = parse(literal)?.first ?: return false
        if (color.toAwt() != target) return false
        val channels = listOf(color.r, color.g, color.b).zip(listOf(target.red, target.green, target.blue))
        return channels.all { (c, t) -> abs(c * 255 - t) <= MAX_OVERSHOOT }
    }

    /**
     * Writes the components with at least [minDecimals] decimals, past the limits of C-01.d only if [minDecimals] is,
     * and puts [alpha] in place of the original alpha, or adds it if the original has none.
     */
    private fun write(values: List<Double>, alpha: String?, original: CssFunctionFormat, minDecimals: Int): String {
        val highestLimit = maxOf(PERCENT_DECIMALS, original.function.components.maxOf { it.numberDecimals })
        fun limit(most: Int) = if (minDecimals > highestLimit) minDecimals else most
        val replacements = original.components.indices.map { i ->
            val number = original.components[i]
            val component = original.function.components[i]
            val (value, most) = when {
                // Achromatic colors keep the original hue.
                component.isHue -> (if (values[i].isNaN()) number.value else fromDegrees(values[i], number.unit)) to limit(1)
                number.unit == "%" -> values[i] / component.percentScale * 100 to limit(PERCENT_DECIMALS)
                else -> values[i] to limit(component.numberDecimals)
            }
            number.range to render(value, number, most, minDecimals)
        }.toMutableList()

        val text = StringBuilder(original.literal)
        val alphaNumber = original.alpha
        if (alphaNumber != null && alpha != null) replacements += alphaNumber.range to alpha
        for ((range, replacement) in replacements.sortedByDescending { it.first.startOffset }) {
            text.replace(range.startOffset, range.endOffset, replacement)
        }
        if (alpha != null && alphaNumber == null) {
            val lastEnd = text.length - (original.literal.length - original.components.last().range.endOffset)
            if (original.legacy) {
                text.insert(lastEnd, original.separator + alpha)
                // rgb → rgba, hsl → hsla, in the case of the original name.
                val upper = original.literal[original.nameLength - 1].isUpperCase()
                if (original.nameLength == 3) text.insert(3, if (upper) 'A' else 'a')
            } else {
                text.insert(lastEnd, " / $alpha")
            }
        }
        return text.toString()
    }

    /**
     * Rounds to at most [most] decimals (or as many as the original had, if more). Drops trailing zeros unless the
     * original had them, and keeps its unit and leading zero.
     */
    private fun render(value: Double, original: CssNumber, most: Int, minDecimals: Int): String {
        val decimals = maxOf(original.decimals, minDecimals).coerceAtMost(maxOf(most, original.decimals))
        var text = BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
        if ('.' in text) {
            val keep = if (original.trailingZeros) original.decimals else 0
            while (text.substringAfter('.').length > keep && text.endsWith('0')) text = text.dropLast(1)
            text = text.removeSuffix(".")
        }
        if (text == "-0") text = "0"
        if (!original.leadingZero) text = text.replaceFirst(Regex("^(-?)0\\."), "$1.")
        return text + original.unit
    }

    private fun MatchResult.toCssNumber(): CssNumber {
        val unit = groupValues[3]
        val digits = value.removeSuffix(unit)
        val fraction = groups[1]?.value ?: groups[2]?.value ?: ""
        val leadingZero = !digits.trimStart('+', '-').startsWith('.')
        return CssNumber(
            TextRange(range.first, range.last + 1), digits.toDouble(), unit, fraction.length, leadingZero, fraction.endsWith('0'),
        )
    }

    private fun toDegrees(value: Double, unit: String) = when (unit.lowercase()) {
        "grad" -> value * 0.9
        "rad" -> Math.toDegrees(value)
        "turn" -> value * 360
        else -> value
    }

    private fun fromDegrees(degrees: Double, unit: String) = when (unit.lowercase()) {
        "grad" -> degrees / 0.9
        "rad" -> Math.toRadians(degrees)
        "turn" -> degrees / 360
        else -> degrees
    }
}
