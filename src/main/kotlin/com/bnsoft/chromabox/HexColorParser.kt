package com.bnsoft.chromabox

import com.intellij.openapi.util.TextRange
import java.awt.Color

/** How a hex color was written, so a picked color can be written back in the same style. */
data class HexFormat(val digits: Int, val upperCase: Boolean) {
    val hasAlpha: Boolean get() = digits == 4 || digits == 8
    val isShort: Boolean get() = digits == 3 || digits == 4
}

data class ColorMatch(val range: TextRange, val color: Color, val format: HexFormat)

object HexColorParser {
    // Not preceded by a word char, '#' or '&' (skips `a#fff`, `##fff`, `&#123;`); not followed by a word char.
    private val LONG = Regex("""(?<![\w#&])#([0-9a-fA-F]{8}|[0-9a-fA-F]{6})(?!\w)""")
    private val ANY = Regex("""(?<![\w#&])#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{4}|[0-9a-fA-F]{3})(?!\w)""")

    fun findAll(text: CharSequence, shortForms: Boolean = true): List<ColorMatch> {
        val regex = if (shortForms) ANY else LONG
        return regex.findAll(text).map { m ->
            val digits = m.groupValues[1]
            ColorMatch(TextRange(m.range.first, m.range.last + 1), parseDigits(digits), formatOf(digits))
        }.toList()
    }

    /** Parses `#…` text; returns null if it isn't a supported hex color. */
    fun parse(text: CharSequence): Color? {
        if (text.length < 4 || text[0] != '#') return null
        val digits = text.substring(1)
        if (digits.length !in setOf(3, 4, 6, 8) || !digits.all { it.isHexDigit() }) return null
        return parseDigits(digits)
    }

    fun format(color: Color, original: HexFormat): String {
        val withAlpha = original.hasAlpha || color.alpha < 255
        val channels = buildList {
            add(color.red); add(color.green); add(color.blue)
            if (withAlpha) add(color.alpha)
        }
        val short = original.isShort && channels.all { (it shr 4) == (it and 0xF) }
        val hex = channels.joinToString("") { c ->
            if (short) Integer.toHexString(c and 0xF) else "%02x".format(c)
        }
        return "#" + if (original.upperCase) hex.uppercase() else hex
    }

    private fun parseDigits(digits: String): Color {
        val full = if (digits.length <= 4) digits.map { "$it$it" }.joinToString("") else digits
        val r = full.substring(0, 2).toInt(16)
        val g = full.substring(2, 4).toInt(16)
        val b = full.substring(4, 6).toInt(16)
        val a = if (full.length == 8) full.substring(6, 8).toInt(16) else 255
        return Color(r, g, b, a)
    }

    // Digit-only values have no case; default them to lower case.
    private fun formatOf(digits: String) = HexFormat(digits.length, digits.any { it in 'A'..'F' })

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
