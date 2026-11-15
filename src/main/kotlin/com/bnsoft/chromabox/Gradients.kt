package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.intellij.openapi.util.TextRange
import kotlin.math.PI

/**
 * One color stop of a gradient at [position] (0 to 1, resolved like CSS does). [range] is where its color is written
 * in the gradient's literal; [write] writes a new color in the same notation, or is null if the stop isn't editable,
 * such as `transparent` (C-04.4).
 */
class GradientStop(
    val color: ChromaColor,
    val position: Double,
    val range: TextRange,
    val text: String,
    val write: ((ChromaColor) -> String?)?,
)

/** A CSS gradient: its stops in order. [GradientStop.color] is the only part that changes when a stop is edited. */
data class Gradient(val literal: String, val stops: List<GradientStop>) {
    fun withColor(index: Int, color: ChromaColor): Gradient {
        val stop = stops[index]
        return copy(stops = stops.toMutableList().also { it[index] = GradientStop(color, stop.position, stop.range, stop.text, stop.write) })
    }

    /** The literal with the stops whose color changed rewritten, and nothing else; null if one can't be written. */
    fun write(original: Gradient): String? {
        val result = StringBuilder(original.literal)
        for (i in stops.indices.reversed()) {
            val before = original.stops[i]
            if (stops[i].color == before.color) continue
            val text = before.write?.invoke(stops[i].color) ?: return null
            result.replace(before.range.startOffset, before.range.endOffset, text)
        }
        return result.toString()
    }
}

data class GradientMatch(val range: TextRange, val gradient: Gradient)

/**
 * CSS gradients (C-04.1): `linear-gradient()`, `radial-gradient()`, `conic-gradient()` and their `repeating-`
 * variants, in any file. Stops are hex colors, CSS color functions, named colors or `transparent`; other stops, such
 * as `var(--x)`, are left out. A gradient needs at least two stops.
 */
object Gradients {
    private val NAME = Regex("""(?<![\w-])(?:repeating-)?(linear|radial|conic)-gradient\(""", RegexOption.IGNORE_CASE)

    // Longer gradients than this aren't looked at.
    private const val MAX_LENGTH = 2000

    private val TRANSPARENT = ChromaColor(0.0, 0.0, 0.0, 0.0)

    private val SPACES = Regex("""\s+""")

    private val POSITION = Regex("""([+-]?(?:\d+(?:\.\d*)?|\.\d+))([a-zA-Z%]*)""")

    fun findAll(text: CharSequence): List<GradientMatch> =
        NAME.findAllAt(text, gradientOffsets(text).map { nameStart(text, it) }).mapNotNull { m ->
            val open = m.range.last
            val close = closingParen(text, open) ?: return@mapNotNull null
            val literal = text.substring(m.range.first, close + 1)
            parse(literal)?.let { GradientMatch(TextRange(m.range.first, close + 1), it) }
        }.toList()

    // Where "gradient(" is, in any case: function names are case-insensitive. A plain loop, it runs over the whole text.
    private fun gradientOffsets(text: CharSequence): Sequence<Int> {
        val result = ArrayList<Int>()
        for (i in 8 until text.length) {
            if (text[i] == '(' && (text[i - 1] == 't' || text[i - 1] == 'T') && text.regionMatches(i - 8, "gradient", 0, 8, ignoreCase = true)) result += i - 8
        }
        return result.asSequence()
    }

    // "gradient(" is preceded by "linear-", "repeating-radial-" and so on.
    private fun nameStart(text: CharSequence, gradient: Int): Int {
        var start = gradient
        while (start > 0 && text[start - 1].let { it.isLetter() || it == '-' }) start--
        return start
    }

    private fun closingParen(text: CharSequence, open: Int): Int? {
        var depth = 0
        for (i in open until minOf(text.length, open + MAX_LENGTH)) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
                '\n' -> return null
            }
        }
        return null
    }

    fun parse(literal: String): Gradient? {
        val match = NAME.matchAt(literal, 0) ?: return null
        if (!literal.endsWith(')')) return null
        val conic = match.groupValues[1].equals("conic", ignoreCase = true)
        val raw = ArrayList<RawStop>()
        for (argument in arguments(literal, match.range.last + 1, literal.length - 1)) {
            raw += stops(literal, argument, conic) ?: continue
        }
        if (raw.size < 2) return null
        val positions = resolve(raw.map { it.position })
        return Gradient(literal, raw.mapIndexed { i, stop -> GradientStop(stop.color, positions[i], stop.range, stop.text, stop.write) })
    }

    private class RawStop(val color: ChromaColor, val position: Double?, val range: TextRange, val text: String, val write: ((ChromaColor) -> String?)?)

    /** Ranges of the top-level arguments between [from] and [to]. */
    private fun arguments(literal: String, from: Int, to: Int): List<TextRange> {
        val result = ArrayList<TextRange>()
        var depth = 0
        var start = from
        for (i in from until to) {
            when (literal[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    result += TextRange(start, i)
                    start = i + 1
                }
            }
        }
        result += TextRange(start, to)
        return result
    }

    /**
     * The stops of one argument: a color and up to two positions, `red 10% 20%` being two stops. Null for an argument
     * that isn't a color stop: a direction, a shape, a color hint, or a color this doesn't know.
     */
    private fun stops(literal: String, argument: TextRange, conic: Boolean): List<RawStop>? {
        var start = argument.startOffset
        while (start < argument.endOffset && literal[start].isWhitespace()) start++
        val end = colorEnd(literal, start, argument.endOffset) ?: return null
        val text = literal.substring(start, end)
        val (color, write) = color(text) ?: return null
        val range = TextRange(start, end)
        val positions = literal.substring(end, argument.endOffset).trim().split(SPACES).filter { it.isNotEmpty() }
        if (positions.size > 2) return null
        val values = positions.map { position(it, conic) }
        return if (values.isEmpty()) listOf(RawStop(color, null, range, text, write))
        else values.map { RawStop(color, it, range, text, write) }
    }

    /** Where a color that starts at [start] ends: a `#` token, a function with its parentheses, or a word. */
    private fun colorEnd(literal: String, start: Int, limit: Int): Int? {
        var i = start
        if (i < limit && literal[i] == '#') i++
        while (i < limit && (literal[i].isLetterOrDigit() || literal[i] == '-')) i++
        if (i == start) return null
        if (i < limit && literal[i] == '(') return closingParen(literal, i)?.takeIf { it < limit }?.plus(1)
        return i
    }

    private fun color(text: String): Pair<ChromaColor, ((ChromaColor) -> String?)?>? {
        if (text.startsWith('#')) {
            val hex = HexColorParser.parse(text) ?: return null
            val format = HexColorParser.formatOf(text.substring(1))
            return ChromaColor.fromAwt(hex) to { color -> HexColorParser.format(color.toAwt(), format) }
        }
        if (text.endsWith(')')) {
            val (color, format) = CssColorFunctions.parse(text) ?: return null
            return color to { picked -> CssColorFunctions.format(picked, format) }
        }
        if (text.equals("transparent", ignoreCase = true)) return TRANSPARENT to null
        val (color, format) = NamedColors.parse(text) ?: return null
        return color to { picked -> NamedColors.format(picked.toAwt(), format) }
    }

    /** A position as a fraction: percentages, and for conic gradients angles; null for lengths, which need a size. */
    private fun position(text: String, conic: Boolean): Double? {
        val number = POSITION.matchEntire(text) ?: return null
        val value = number.groupValues[1].toDouble()
        return when (number.groupValues[2].lowercase()) {
            "%" -> value / 100
            "" -> if (value == 0.0) 0.0 else null
            "deg" -> if (conic) value / 360 else null
            "turn" -> if (conic) value else null
            "rad" -> if (conic) value / (2 * PI) else null
            "grad" -> if (conic) value / 400 else null
            else -> null
        }
    }

    /**
     * Fills in missing positions like CSS: the first defaults to 0, the last to 1, others are spread evenly between
     * their neighbors, and a position before an earlier one moves up to it.
     */
    internal fun resolve(positions: List<Double?>): List<Double> {
        val result = positions.toMutableList()
        if (result.first() == null) result[0] = 0.0
        if (result.last() == null) result[result.lastIndex] = 1.0
        var max = Double.NEGATIVE_INFINITY
        for (i in result.indices) result[i]?.let { max = maxOf(max, it); result[i] = max }
        var i = 0
        while (i < result.size) {
            if (result[i] != null) {
                i++
                continue
            }
            val from = i - 1
            var to = i
            while (result[to] == null) to++
            val start = result[from]!!
            val step = (result[to]!! - start) / (to - from)
            for (j in i until to) result[j] = start + step * (j - from)
            i = to
        }
        return result.map { it!! }
    }
}
