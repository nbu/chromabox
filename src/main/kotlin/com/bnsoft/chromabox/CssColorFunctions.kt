package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.color.ColorSpaces
import com.bnsoft.chromabox.color.Hsl
import com.bnsoft.chromabox.color.Hwb
import com.intellij.openapi.util.TextRange
import java.math.BigDecimal
import java.math.RoundingMode

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
 * How a CSS color function was written: the whole [literal], its components (channels, or hue and two
 * percentages) and alpha. [separator] is the text between the last two components, reused when alpha is added.
 */
data class CssFunctionFormat(
    val literal: String,
    val kind: Kind,
    val nameLength: Int,
    val components: List<CssNumber>,
    val alpha: CssNumber?,
    val legacy: Boolean,
    val separator: String,
) {
    enum class Kind { RGB, HSL, HWB }
}

data class CssColorMatch(val range: TextRange, val color: ChromaColor, val format: CssFunctionFormat)

/** `rgb()`, `rgba()`, `hsl()`, `hsla()` and `hwb()` in legacy (comma) and modern (space and slash) syntax. */
object CssColorFunctions {
    private val FUNCTION = Regex("""(?<![\w-])(rgba?|hsla?|hwb)\([^()]*\)""", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""[+-]?(?:\d+(?:\.(\d*))?|\.(\d+))(?:[eE][+-]?\d+)?(%|deg|grad|rad|turn)?""", RegexOption.IGNORE_CASE)
    private val SEPARATOR = Regex("""\s*([,/])?\s*""")
    private val HUE_UNITS = setOf("", "deg", "grad", "rad", "turn")

    // Most decimals written per component kind (C-01.d).
    private const val CHANNEL_DECIMALS = 0
    private const val PERCENT_DECIMALS = 2
    private const val HUE_DECIMALS = 1
    private const val ALPHA_DECIMALS = 2

    fun findAll(text: CharSequence): List<CssColorMatch> = FUNCTION.findAll(text).mapNotNull { m ->
        val (color, format) = parse(m.value) ?: return@mapNotNull null
        CssColorMatch(TextRange(m.range.first, m.range.last + 1), color, format)
    }.toList()

    fun parse(literal: String): Pair<ChromaColor, CssFunctionFormat>? {
        val open = literal.indexOf('(')
        if (open < 0 || !literal.endsWith(')')) return null
        val name = literal.substring(0, open).lowercase()
        val kind = when (name) {
            "rgb", "rgba" -> CssFunctionFormat.Kind.RGB
            "hsl", "hsla" -> CssFunctionFormat.Kind.HSL
            "hwb" -> CssFunctionFormat.Kind.HWB
            else -> return null
        }

        val numbers = ArrayList<CssNumber>()
        val separators = ArrayList<String>()
        var pos = SEPARATOR.matchAt(literal, open + 1)!!.range.last + 1
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
        if (!(legacy && numbers.size in 3..4) && !modern) return null
        if (legacy && kind == CssFunctionFormat.Kind.HWB) return null

        val components = numbers.take(3)
        val alpha = numbers.getOrNull(3)
        if (alpha != null && alpha.unit !in setOf("", "%")) return null
        val alphaValue = alpha?.let { (if (it.unit == "%") it.value / 100 else it.value).coerceIn(0.0, 1.0) } ?: 1.0

        val color = when (kind) {
            CssFunctionFormat.Kind.RGB -> {
                if (components.any { it.unit !in setOf("", "%") }) return null
                val (r, g, b) = components.map { if (it.unit == "%") it.value / 100 else it.value / 255 }
                ChromaColor(r, g, b, alphaValue)
            }
            CssFunctionFormat.Kind.HSL, CssFunctionFormat.Kind.HWB -> {
                val (hue, first, second) = components
                if (hue.unit.lowercase() !in HUE_UNITS || first.unit !in setOf("", "%") || second.unit !in setOf("", "%")) return null
                val h = toDegrees(hue.value, hue.unit)
                val x = (first.value / 100).coerceIn(0.0, 1.0)
                val y = (second.value / 100).coerceIn(0.0, 1.0)
                if (kind == CssFunctionFormat.Kind.HSL) ColorSpaces.fromHsl(Hsl(h, x, y), alphaValue)
                else ColorSpaces.fromHwb(Hwb(h, x, y), alphaValue)
            }
        }
        return color to CssFunctionFormat(literal, kind, open, components, alpha, legacy, separators.last())
    }

    /**
     * Writes [color] the way [original] was written: same function name, separators, units, number style and
     * whitespace. Adds alpha in the style of the same syntax if [color] is translucent and [original] has none.
     * Uses the fewest decimals that still give the same 8-bit color.
     */
    fun format(color: ChromaColor, original: CssFunctionFormat): String {
        val target = color.toAwt()
        val values = componentValues(color, original)
        val withAlpha = original.alpha != null || target.alpha < 255
        val candidates = (0..PERCENT_DECIMALS).map { write(values, color.alpha, withAlpha, original, it) }
        return candidates.firstOrNull { parse(it)?.first?.toAwt() == target } ?: candidates.last()
    }

    private fun componentValues(color: ChromaColor, original: CssFunctionFormat): List<Double> {
        val hue = original.components.first()
        return when (original.kind) {
            CssFunctionFormat.Kind.RGB -> listOf(color.r, color.g, color.b)
                .zip(original.components) { c, n -> if (n.unit == "%") c * 100 else c * 255 }
            CssFunctionFormat.Kind.HSL -> {
                val hsl = ColorSpaces.toHsl(color)
                listOf(hue(hsl.h, hue), hsl.s * 100, hsl.l * 100)
            }
            CssFunctionFormat.Kind.HWB -> {
                val hwb = ColorSpaces.toHwb(color)
                listOf(hue(hwb.h, hue), hwb.w * 100, hwb.b * 100)
            }
        }
    }

    /** The hue in the original's unit; achromatic colors keep the original hue. */
    private fun hue(degrees: Double, original: CssNumber): Double =
        if (degrees.isNaN()) original.value else fromDegrees(degrees, original.unit)

    private fun write(values: List<Double>, alpha: Double, withAlpha: Boolean, original: CssFunctionFormat, minDecimals: Int): String {
        val replacements = original.components.zip(values).mapIndexed { index, (number, value) ->
            val most = when {
                original.kind != CssFunctionFormat.Kind.RGB && index == 0 -> HUE_DECIMALS
                number.unit == "%" || original.kind != CssFunctionFormat.Kind.RGB -> PERCENT_DECIMALS
                else -> CHANNEL_DECIMALS
            }
            number.range to render(value, number, most, minDecimals)
        }.toMutableList()

        val text = StringBuilder(original.literal)
        val alphaNumber = original.alpha
        if (alphaNumber != null) {
            val value = if (alphaNumber.unit == "%") alpha * 100 else alpha
            replacements += alphaNumber.range to render(value, alphaNumber, ALPHA_DECIMALS, minDecimals)
        }
        for ((range, replacement) in replacements.sortedByDescending { it.first.startOffset }) {
            text.replace(range.startOffset, range.endOffset, replacement)
        }
        if (withAlpha && alphaNumber == null) {
            val lastEnd = text.length - (original.literal.length - original.components.last().range.endOffset)
            if (original.legacy) {
                val style = CssNumber(TextRange.EMPTY_RANGE, alpha, "", 0, leadingZero = true)
                text.insert(lastEnd, original.separator + render(alpha, style, ALPHA_DECIMALS, minDecimals))
                // rgb → rgba, hsl → hsla, in the case of the original name.
                val upper = original.literal[original.nameLength - 1].isUpperCase()
                if (original.nameLength == 3) text.insert(3, if (upper) 'A' else 'a')
            } else {
                val style = CssNumber(TextRange.EMPTY_RANGE, alpha * 100, "%", 0, leadingZero = true)
                text.insert(lastEnd, " / " + render(alpha * 100, style, PERCENT_DECIMALS, minDecimals))
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
