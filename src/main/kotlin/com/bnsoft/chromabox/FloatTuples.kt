package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.intellij.openapi.util.TextRange

data class FloatTupleMatch(val range: TextRange, val color: ChromaColor, val format: ConstructorFormat)

/**
 * Float tuples (C-01.7): `[0.1, 0.2, 0.3]`, `(1.0, 0.5, 0.0, 1.0)`. Three or four numbers from 0 to 1, at least one
 * with a decimal point, so integer tuples like `(1, 0, 0)` aren't colors. A `(` right after a name or a closing
 * bracket is a call, not a tuple. Written back like a constructor: same brackets, separators and number style, and
 * numbers with a decimal point keep at least as many decimals.
 */
object FloatTuples {
    private const val FLOAT = """(\d+(?:\.\d*)?|\.\d+)"""

    private val TUPLE = Regex(
        """(?<![\w)\]])([\[(])\s*$FLOAT\s*,\s*$FLOAT(\s*,\s*)$FLOAT\s*(?:,\s*$FLOAT\s*)?([\])])""",
    )

    fun findAll(text: CharSequence): List<FloatTupleMatch> = TUPLE.findAllAt(text, text.offsetsOf('[', '(')).mapNotNull(::match).toList()

    fun parse(literal: String): Pair<ChromaColor, ConstructorFormat>? =
        TUPLE.matchEntire(literal)?.let(::match)?.let { it.color to it.format }

    private fun match(m: MatchResult): FloatTupleMatch? {
        val brackets = m.groupValues[1] + m.groupValues[7]
        if (brackets != "[]" && brackets != "()") return null
        val groups = listOf(2, 3, 5, 6)
        if (groups.none { '.' in m.groupValues[it] }) return null
        val arguments = groups.mapIndexedNotNull { channel, group ->
            val g = m.groups[group] ?: return@mapIndexedNotNull null
            val parsed = CssColorFunctions.number(g.value, g.range.first - m.range.first) ?: return null
            if (parsed.value !in 0.0..1.0) return null
            // Keep a written decimal point, `0.0` rather than `0`, so the tuple is still found after editing.
            val number = parsed.copy(trailingZeros = parsed.decimals > 0)
            ChannelArgument(number, channel, integer = false)
        }
        val channels = DoubleArray(4) { 1.0 }
        for (argument in arguments) channels[argument.channel] = argument.number.value
        val alphaPrefix = if (arguments.size == 3) m.groupValues[4] else null
        val format = ConstructorFormat(m.value, null, arguments, alphaPrefix)
        val color = ChromaColor(channels[0], channels[1], channels[2], channels[3])
        return FloatTupleMatch(TextRange(m.range.first, m.range.last + 1), color, format)
    }
}
