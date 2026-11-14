package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.intellij.openapi.util.TextRange
import java.awt.Color
import kotlin.math.roundToInt

/** One channel argument of a constructor; [channel] is 0..3 for red, green, blue, alpha; `100%` isn't used. */
data class ChannelArgument(val number: CssNumber, val channel: Int, val integer: Boolean)

/**
 * How a color constructor was written. Either [hex] (with its range in [literal]) or [arguments] is set.
 * [alphaPrefix] is inserted with the alpha value after the last argument to add alpha; null if alpha is present.
 */
data class ConstructorFormat(
    val literal: String,
    val hex: Pair<TextRange, IntColorFormat>?,
    val arguments: List<ChannelArgument>,
    val alphaPrefix: String?,
)

data class ConstructorColorMatch(val range: TextRange, val color: ChromaColor, val format: ConstructorFormat)

/**
 * Color constructors (C-01.6): Compose and Flutter `Color(0xFF112233)`, Flutter `Color.fromARGB(255, 1, 2, 3)`,
 * Java AWT `new Color(1, 2, 3)`, and UIKit, AppKit and SwiftUI `UIColor(red: 0.1, green: 0.2, blue: 0.3, alpha: 1)`.
 */
object ConstructorColors {
    private const val INT = """(\d+)"""
    private const val FLOAT = """((?:\d+(?:\.\d*)?|\.\d+))"""

    private val HEX = Regex("""(?<![\w.])Color\(\s*(0[xX][0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?)[uUlL]*\s*\)""")
    private val FROM_ARGB = Regex("""(?<![\w.])Color\.fromARGB\(\s*$INT\s*,\s*$INT\s*,\s*$INT\s*,\s*$INT\s*\)""")
    private val AWT = Regex("""(?<![\w.])new\s+Color\(\s*$INT\s*,\s*$INT(\s*,\s*)$INT\s*(?:,\s*$INT\s*)?\)""")
    private val SWIFT = Regex(
        """(?<![\w.])(UIColor|NSColor|Color)\(\s*red:\s*$FLOAT\s*,\s*green:\s*$FLOAT(\s*,\s*)blue:\s*$FLOAT\s*(?:,\s*(?:alpha|opacity):\s*$FLOAT\s*)?\)""",
    )

    // Most decimals for float channels (C-01.d), unless no number within that gives the picked channel.
    private const val FLOAT_DECIMALS = 2
    private const val EXACT_DECIMALS = 6

    fun findAll(text: CharSequence): List<ConstructorColorMatch> =
        text.offsetsOf("Color").toList().let { colors ->
            listOf(HEX to ::colorStart, FROM_ARGB to ::colorStart, AWT to ::newStart, SWIFT to ::swiftStart).flatMap { (regex, start) ->
                regex.findAllAt(text, colors.asSequence().mapNotNull { start(text, it) }).mapNotNull { match(it) }.toList()
            }
        }

    // Where each constructor can start, given an offset of "Color" in the text.
    private fun colorStart(text: CharSequence, color: Int): Int = color

    private fun swiftStart(text: CharSequence, color: Int): Int =
        if (color >= 2 && (text.startsWith("UI", color - 2) || text.startsWith("NS", color - 2))) color - 2 else color

    private fun newStart(text: CharSequence, color: Int): Int? {
        var start = color
        while (start > 0 && text[start - 1].isWhitespace()) start--
        return if (start < color && start >= 3 && text.startsWith("new", start - 3)) start - 3 else null
    }

    fun parse(literal: String): Pair<ChromaColor, ConstructorFormat>? =
        listOf(HEX, FROM_ARGB, AWT, SWIFT).firstNotNullOfOrNull { it.matchEntire(literal)?.let(::match) }
            ?.let { it.color to it.format }

    private fun match(m: MatchResult): ConstructorColorMatch? {
        val literal = m.value
        val range = TextRange(m.range.first, m.range.last + 1)
        if (m.groups.size == 2) {
            // Compose and Flutter: always ARGB, whatever the setting for standalone integers.
            val hex = m.groups[1]!!
            val (color, format) = IntegerColors.parse(hex.value, IntColorOrder.ARGB) ?: return null
            val hexRange = TextRange(hex.range.first - m.range.first, hex.range.last + 1 - m.range.first)
            return ConstructorColorMatch(range, color, ConstructorFormat(literal, hexRange to format, emptyList(), null))
        }

        fun argument(group: Int, channel: Int, integer: Boolean): ChannelArgument? {
            val g = m.groups[group] ?: return null
            val number = CssColorFunctions.number(g.value, g.range.first - m.range.first) ?: return null
            return ChannelArgument(number, channel, integer)
        }

        val (arguments, alphaPrefix) = when {
            literal.startsWith("Color.fromARGB") ->
                listOf(argument(1, 3, true), argument(2, 0, true), argument(3, 1, true), argument(4, 2, true)) to null
            literal.startsWith("new") -> {
                val alpha = argument(5, 3, true)
                listOf(argument(1, 0, true), argument(2, 1, true), argument(4, 2, true), alpha) to
                    (if (alpha == null) m.groupValues[3] else null)
            }
            else -> {
                val alpha = argument(6, 3, false)
                val label = if (m.groupValues[1] == "Color") "opacity: " else "alpha: "
                listOf(argument(2, 0, false), argument(3, 1, false), argument(5, 2, false), alpha) to
                    (if (alpha == null) m.groupValues[4] + label else null)
            }
        }
        val present = arguments.filterNotNull()
        val channels = DoubleArray(4) { 1.0 }
        for (argument in present) {
            val value = if (argument.integer) argument.number.value / 255 else argument.number.value
            if (value !in 0.0..1.0) return null
            channels[argument.channel] = value
        }
        val color = ChromaColor(channels[0], channels[1], channels[2], channels[3])
        return ConstructorColorMatch(range, color, ConstructorFormat(literal, null, present, alphaPrefix))
    }

    /** Writes [color] like [original]: same constructor, labels, separators and number style. */
    fun format(color: Color, original: ConstructorFormat): String {
        val text = StringBuilder(original.literal)
        original.hex?.let { (range, format) ->
            return text.replace(range.startOffset, range.endOffset, IntegerColors.format(color, format)).toString()
        }
        val channels = listOf(color.red, color.green, color.blue, color.alpha)
        if (original.alphaPrefix != null && color.alpha < 255) {
            val last = original.arguments.maxBy { it.number.range.startOffset }
            val style = last.number.copy(range = TextRange.EMPTY_RANGE, decimals = 0, trailingZeros = false)
            text.insert(last.number.range.endOffset, original.alphaPrefix + write(color.alpha, ChannelArgument(style, 3, last.integer)))
        }
        for (argument in original.arguments.sortedByDescending { it.number.range.startOffset }) {
            val range = argument.number.range
            text.replace(range.startOffset, range.endOffset, write(channels[argument.channel], argument))
        }
        return text.toString()
    }

    /** An 8-bit [channel] value as [argument] is written: an integer, or the fewest decimals that give it back. */
    private fun write(channel: Int, argument: ChannelArgument): String {
        if (argument.integer) return channel.toString()
        val value = channel / 255.0
        val candidates = (0..EXACT_DECIMALS).asSequence().map { CssColorFunctions.render(value, argument.number, maxOf(FLOAT_DECIMALS, it), it) }
        return candidates.firstOrNull { (it.toDouble() * 255).roundToInt() == channel } ?: candidates.last()
    }
}
