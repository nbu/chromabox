package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.intellij.openapi.util.TextRange
import java.awt.Color

/** How a named color was written: all lower case, all upper case, or capitalized (`Tomato`). */
enum class NameCase {
    LOWER, UPPER, CAPITALIZED;

    fun apply(name: String): String = when (this) {
        LOWER -> name
        UPPER -> name.uppercase()
        CAPITALIZED -> name.replaceFirstChar { it.uppercaseChar() }
    }

    companion object {
        fun of(text: String): NameCase = when {
            text == text.lowercase() -> LOWER
            text == text.uppercase() -> UPPER
            else -> CAPITALIZED
        }
    }
}

/** [member] is true after a `.`, as in `UIColor.red`: code, where hex can't replace the name. */
data class NamedColorFormat(val name: String, val case: NameCase, val member: Boolean = false)

data class NamedColorMatch(val range: TextRange, val color: ChromaColor, val format: NamedColorFormat)

/**
 * CSS named colors (C-01.4), matched case-insensitively and word-bounded. A picked color is written as a name if it
 * has one, in the original's case, else as hex.
 */
object NamedColors {
    /** The CSS Color 4 named colors, lower case, in spec order. Some colors have two names: aqua and cyan, gray and grey. */
    val COLORS: Map<String, Int> = linkedMapOf(
        "aliceblue" to 0xf0f8ff, "antiquewhite" to 0xfaebd7, "aqua" to 0x00ffff, "aquamarine" to 0x7fffd4,
        "azure" to 0xf0ffff, "beige" to 0xf5f5dc, "bisque" to 0xffe4c4, "black" to 0x000000,
        "blanchedalmond" to 0xffebcd, "blue" to 0x0000ff, "blueviolet" to 0x8a2be2, "brown" to 0xa52a2a,
        "burlywood" to 0xdeb887, "cadetblue" to 0x5f9ea0, "chartreuse" to 0x7fff00, "chocolate" to 0xd2691e,
        "coral" to 0xff7f50, "cornflowerblue" to 0x6495ed, "cornsilk" to 0xfff8dc, "crimson" to 0xdc143c,
        "cyan" to 0x00ffff, "darkblue" to 0x00008b, "darkcyan" to 0x008b8b, "darkgoldenrod" to 0xb8860b,
        "darkgray" to 0xa9a9a9, "darkgreen" to 0x006400, "darkgrey" to 0xa9a9a9, "darkkhaki" to 0xbdb76b,
        "darkmagenta" to 0x8b008b, "darkolivegreen" to 0x556b2f, "darkorange" to 0xff8c00, "darkorchid" to 0x9932cc,
        "darkred" to 0x8b0000, "darksalmon" to 0xe9967a, "darkseagreen" to 0x8fbc8f, "darkslateblue" to 0x483d8b,
        "darkslategray" to 0x2f4f4f, "darkslategrey" to 0x2f4f4f, "darkturquoise" to 0x00ced1, "darkviolet" to 0x9400d3,
        "deeppink" to 0xff1493, "deepskyblue" to 0x00bfff, "dimgray" to 0x696969, "dimgrey" to 0x696969,
        "dodgerblue" to 0x1e90ff, "firebrick" to 0xb22222, "floralwhite" to 0xfffaf0, "forestgreen" to 0x228b22,
        "fuchsia" to 0xff00ff, "gainsboro" to 0xdcdcdc, "ghostwhite" to 0xf8f8ff, "gold" to 0xffd700,
        "goldenrod" to 0xdaa520, "gray" to 0x808080, "green" to 0x008000, "greenyellow" to 0xadff2f,
        "grey" to 0x808080, "honeydew" to 0xf0fff0, "hotpink" to 0xff69b4, "indianred" to 0xcd5c5c,
        "indigo" to 0x4b0082, "ivory" to 0xfffff0, "khaki" to 0xf0e68c, "lavender" to 0xe6e6fa,
        "lavenderblush" to 0xfff0f5, "lawngreen" to 0x7cfc00, "lemonchiffon" to 0xfffacd, "lightblue" to 0xadd8e6,
        "lightcoral" to 0xf08080, "lightcyan" to 0xe0ffff, "lightgoldenrodyellow" to 0xfafad2, "lightgray" to 0xd3d3d3,
        "lightgreen" to 0x90ee90, "lightgrey" to 0xd3d3d3, "lightpink" to 0xffb6c1, "lightsalmon" to 0xffa07a,
        "lightseagreen" to 0x20b2aa, "lightskyblue" to 0x87cefa, "lightslategray" to 0x778899, "lightslategrey" to 0x778899,
        "lightsteelblue" to 0xb0c4de, "lightyellow" to 0xffffe0, "lime" to 0x00ff00, "limegreen" to 0x32cd32,
        "linen" to 0xfaf0e6, "magenta" to 0xff00ff, "maroon" to 0x800000, "mediumaquamarine" to 0x66cdaa,
        "mediumblue" to 0x0000cd, "mediumorchid" to 0xba55d3, "mediumpurple" to 0x9370db, "mediumseagreen" to 0x3cb371,
        "mediumslateblue" to 0x7b68ee, "mediumspringgreen" to 0x00fa9a, "mediumturquoise" to 0x48d1cc,
        "mediumvioletred" to 0xc71585, "midnightblue" to 0x191970, "mintcream" to 0xf5fffa, "mistyrose" to 0xffe4e1,
        "moccasin" to 0xffe4b5, "navajowhite" to 0xffdead, "navy" to 0x000080, "oldlace" to 0xfdf5e6,
        "olive" to 0x808000, "olivedrab" to 0x6b8e23, "orange" to 0xffa500, "orangered" to 0xff4500,
        "orchid" to 0xda70d6, "palegoldenrod" to 0xeee8aa, "palegreen" to 0x98fb98, "paleturquoise" to 0xafeeee,
        "palevioletred" to 0xdb7093, "papayawhip" to 0xffefd5, "peachpuff" to 0xffdab9, "peru" to 0xcd853f,
        "pink" to 0xffc0cb, "plum" to 0xdda0dd, "powderblue" to 0xb0e0e6, "purple" to 0x800080,
        "rebeccapurple" to 0x663399, "red" to 0xff0000, "rosybrown" to 0xbc8f8f, "royalblue" to 0x4169e1,
        "saddlebrown" to 0x8b4513, "salmon" to 0xfa8072, "sandybrown" to 0xf4a460, "seagreen" to 0x2e8b57,
        "seashell" to 0xfff5ee, "sienna" to 0xa0522d, "silver" to 0xc0c0c0, "skyblue" to 0x87ceeb,
        "slateblue" to 0x6a5acd, "slategray" to 0x708090, "slategrey" to 0x708090, "snow" to 0xfffafa,
        "springgreen" to 0x00ff7f, "steelblue" to 0x4682b4, "tan" to 0xd2b48c, "teal" to 0x008080,
        "thistle" to 0xd8bfd8, "tomato" to 0xff6347, "turquoise" to 0x40e0d0, "violet" to 0xee82ee,
        "wheat" to 0xf5deb3, "white" to 0xffffff, "whitesmoke" to 0xf5f5f5, "yellow" to 0xffff00,
        "yellowgreen" to 0x9acd32,
    )

    private val NAME_LENGTHS = COLORS.keys.minOf { it.length }..COLORS.keys.maxOf { it.length }

    /**
     * Whole words that are names, in any case. Hyphens count as word characters, so "red-500" and "text-red" aren't
     * names, and neither are variables and selectors like `$red`, `@red`, `#red`. A hand-written word scan, since a
     * regex of 148 alternatives is too slow for large files (N-02).
     */
    fun findAll(text: CharSequence): List<NamedColorMatch> {
        val matches = ArrayList<NamedColorMatch>()
        var start = 0
        while (start < text.length) {
            if (!text[start].isAsciiLetter()) {
                start++
                continue
            }
            var end = start + 1
            while (end < text.length && text[end].isAsciiLetter()) end++
            val before = if (start > 0) text[start - 1] else ' '
            val after = if (end < text.length) text[end] else ' '
            if (end - start in NAME_LENGTHS && !before.isNameChar() && before !in "$@#" && !after.isNameChar()) {
                parse(text.substring(start, end))?.let { (color, format) ->
                    matches += NamedColorMatch(TextRange(start, end), color, format.copy(member = before == '.'))
                }
            }
            start = end
        }
        return matches
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    // Regex `\w` and the hyphen.
    private fun Char.isNameChar() = isAsciiLetter() || this in '0'..'9' || this == '_' || this == '-'

    fun parse(literal: String): Pair<ChromaColor, NamedColorFormat>? {
        val rgb = COLORS[literal.lowercase()] ?: return null
        return ChromaColor.fromAwt(Color(rgb)) to NamedColorFormat(literal.lowercase(), NameCase.of(literal))
    }

    /**
     * The name of [color] in the original's case, preferring the original name and then its spelling of gray or grey;
     * hex if it has no name or has alpha. Null if it has none after a `.`, where hex would break the code.
     */
    fun format(color: Color, original: NamedColorFormat): String? {
        val rgb = color.rgb and 0xffffff
        val names = if (color.alpha == 255) COLORS.filterValues { it == rgb }.keys else emptySet()
        val grey = "grey" in original.name
        val name = names.firstOrNull { it == original.name }
            ?: names.firstOrNull { ("grey" in it) == grey && ("gray" in it) == !grey && ("gray" in it || "grey" in it) }
            ?: names.firstOrNull()
        if (name != null) return original.case.apply(name)
        if (original.member) return null
        return HexColorParser.format(color, HexFormat(6, original.case == NameCase.UPPER))
    }
}
