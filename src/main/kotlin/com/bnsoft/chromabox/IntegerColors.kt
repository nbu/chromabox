package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.intellij.openapi.util.TextRange
import java.awt.Color

/** Channel order of 8-digit integer colors. */
enum class IntColorOrder { ARGB, RGBA }

/** How a `0x` color was written. 6 digits are opaque RGB in either order. */
data class IntColorFormat(val prefix: String, val digits: Int, val upperCase: Boolean, val order: IntColorOrder)

data class IntColorMatch(val range: TextRange, val color: ChromaColor, val format: IntColorFormat)

/** Integer colors: `0xAARRGGBB` (or `0xRRGGBBAA`) and, inside constructors, `0xRRGGBB`. */
object IntegerColors {
    // Standalone, only 8 digits (C-01.5). Kotlin/Java suffixes like `u` or `L` may follow but aren't part of the color.
    private val STANDALONE = Regex("""(?<![\w.])0[xX][0-9a-fA-F]{8}(?=[uUlL]*(?![\w.]))""")

    fun findAll(text: CharSequence, order: IntColorOrder): List<IntColorMatch> = STANDALONE.findAllAt(text, text.offsetsOf('x', 'X').filter { it > 0 && text[it - 1] == '0' }.map { it - 1 })
        .mapNotNull { m ->
        val (color, format) = parse(m.value, order) ?: return@mapNotNull null
        IntColorMatch(TextRange(m.range.first, m.range.last + 1), color, format)
    }.toList()

    fun parse(literal: String, order: IntColorOrder): Pair<ChromaColor, IntColorFormat>? {
        if (literal.length < 2 || literal[0] != '0' || literal[1] !in "xX") return null
        val digits = literal.substring(2)
        if (digits.length != 6 && digits.length != 8 || !digits.all { it.isHexDigit() }) return null
        val value = digits.toLong(16)
        val color = when {
            digits.length == 6 -> Color(value.toInt())
            order == IntColorOrder.ARGB -> Color(value.toInt(), true)
            else -> Color((value shr 24).toInt() and 0xFF, (value shr 16).toInt() and 0xFF, (value shr 8).toInt() and 0xFF, value.toInt() and 0xFF)
        }
        return ChromaColor.fromAwt(color) to IntColorFormat(literal.substring(0, 2), digits.length, digits.any { it in 'A'..'F' }, order)
    }

    /** Writes [color] like [original]; 6 digits become 8 if [color] is translucent (C-01.c). */
    fun format(color: Color, original: IntColorFormat): String {
        val withAlpha = original.digits == 8 || color.alpha < 255
        val rgb = listOf(color.red, color.green, color.blue)
        val channels = when {
            !withAlpha -> rgb
            original.order == IntColorOrder.ARGB -> listOf(color.alpha) + rgb
            else -> rgb + color.alpha
        }
        val hex = channels.joinToString("") { "%02x".format(it) }
        return original.prefix + if (original.upperCase) hex.uppercase() else hex
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
