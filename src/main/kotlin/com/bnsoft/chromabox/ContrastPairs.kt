package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.intellij.openapi.util.TextRange

/** A color found in the text. */
data class ColorAt(val range: TextRange, val color: ChromaColor)

/** Two colors that belong together. The badge goes after [second], whichever of the two comes later in the text. */
data class ContrastPair(val foreground: ColorAt, val background: ColorAt) {
    val second: ColorAt get() = if (foreground.range.startOffset > background.range.startOffset) foreground else background
}

/**
 * Finds colors that belong together (C-02.1), by heuristics applied in order. A color is in at most one pair.
 * 1. A foreground and background key in one block: `fg`/`bg`, `foreground`/`background`, `color`/`background-color`,
 *    `text`/`surface`, and Material's `onPrimary`/`primary`.
 * 2. Exactly two colors on one line.
 */
object ContrastPairs {
    /** Background key for each foreground key, after [normalize]. */
    private val PARTNERS = mapOf("fg" to "bg", "foreground" to "background", "color" to "background", "text" to "surface")

    private val TOML_HEADER = Regex("""\[[^\[\]]+]\s*""")

    fun find(text: CharSequence, colors: List<ColorAt>): List<ContrastPair> {
        val sorted = colors.sortedBy { it.range.startOffset }
        val paired = HashSet<ColorAt>()
        val pairs = byKeys(text, sorted, paired)
        return (pairs + byLines(text, sorted, paired)).sortedBy { it.second.range.startOffset }
    }

    private class Keyed(val color: ColorAt, val key: String, val partner: String?, val scope: Any)

    private fun byKeys(text: CharSequence, colors: List<ColorAt>, paired: MutableSet<ColorAt>): List<ContrastPair> {
        val keys = colors.mapNotNull { color -> keyBefore(text, color.range.startOffset)?.let { color to it } }
        if (keys.isEmpty()) return emptyList()
        val scopes = scopes(text, keys.map { it.first.range.startOffset }.distinct())
        val keyed = keys.map { (color, raw) ->
            val key = normalize(raw)
            Keyed(color, key, partnerOf(raw, key), scopes.getValue(color.range.startOffset))
        }
        val pairs = ArrayList<ContrastPair>()
        for (fg in keyed) {
            val partner = fg.partner ?: continue
            if (fg.color in paired) continue
            val bg = keyed.filter { it.scope == fg.scope && it.key == partner && it.color !in paired }
                .minByOrNull { kotlin.math.abs(it.color.range.startOffset - fg.color.range.startOffset) } ?: continue
            paired += fg.color
            paired += bg.color
            pairs += ContrastPair(fg.color, bg.color)
        }
        return pairs
    }

    /**
     * The key right before the color at [offset]: `fg: `, `"background": "`, `color = `, `--on-primary: `. Scanned
     * backwards by hand; it runs for every color.
     */
    private fun keyBefore(text: CharSequence, offset: Int): String? {
        var i = offset
        fun skipSpaces() { while (i > 0 && (text[i - 1] == ' ' || text[i - 1] == '\t')) i-- }
        fun skipQuote() { if (i > 0 && (text[i - 1] == '"' || text[i - 1] == '\'')) i-- }
        skipSpaces()
        skipQuote()
        skipSpaces()
        if (i == 0 || (text[i - 1] != ':' && text[i - 1] != '=')) return null
        i--
        skipSpaces()
        skipQuote()
        val end = i
        while (i > 0 && text[i - 1].let { it.isLetterOrDigit() || it == '_' || it == '-' }) i--
        while (i < end && (text[i] == '-' || text[i].isDigit())) i++
        return if (i < end) text.substring(i, end) else null
    }

    /** Lower case without `-` and `_`, and without a trailing "color": `background-color` and `bgColor` are keys too. */
    private fun normalize(key: String): String {
        val plain = key.lowercase().replace("-", "").replace("_", "")
        return plain.removeSuffix("color").ifEmpty { plain }
    }

    /** The background key that goes with [raw] as a foreground: `bg` for `fg`, `primary` for `onPrimary`. */
    private fun partnerOf(raw: String, key: String): String? {
        PARTNERS[key]?.let { return it }
        // Material: on<X> is the foreground over <X>. "on" must be followed by a capital or a separator.
        val trimmed = raw.trimStart('-', '_')
        if (trimmed.length > 4 && trimmed.startsWith("on", ignoreCase = true) && (trimmed[2].isUpperCase() || trimmed[2] in "-_")) {
            return normalize(trimmed.substring(2))
        }
        return null
    }

    private fun byLines(text: CharSequence, colors: List<ColorAt>, paired: Set<ColorAt>): List<ContrastPair> {
        val pairs = ArrayList<ContrastPair>()
        var i = 0
        while (i < colors.size) {
            var j = i + 1
            val lineEnd = lineEnd(text, colors[i].range.startOffset)
            while (j < colors.size && colors[j].range.startOffset < lineEnd) j++
            val line = colors.subList(i, j)
            if (line.size == 2 && line.none { it in paired }) {
                val (a, b) = line
                // The translucent one is the foreground, if just one is.
                pairs += if (b.color.alpha < 1 && a.color.alpha >= 1) ContrastPair(b, a) else ContrastPair(a, b)
            }
            i = j
        }
        return pairs
    }

    private fun lineEnd(text: CharSequence, offset: Int): Int {
        var end = offset
        while (end < text.length && text[end] != '\n') end++
        return end
    }

    /**
     * The block each of [offsets] (ascending) is in: the innermost open bracket before it, plus, for indentation-based
     * formats such as YAML, the nearest line before it that is indented less. A TOML `[table]` header ranks above
     * top-level keys, so it starts a block of its own. Brackets in strings and comments count too; it's a heuristic.
     * One pass over the text.
     */
    private fun scopes(text: CharSequence, offsets: List<Int>): Map<Int, Any> {
        val result = HashMap<Int, Any>()
        val brackets = ArrayList<Int>()
        // Lines that could still be the parent of a later line, as (indent, line start); indents strictly increase.
        val parents = ArrayList<Pair<Int, Int>>()
        var parent = -1
        var next = 0
        var lineStart = 0
        var i = 0
        while (next < offsets.size) {
            if (i == lineStart) {
                var first = i
                while (first < text.length && (text[first] == ' ' || text[first] == '\t')) first++
                if (first < text.length && text[first] != '\n' && text[first] != '\r') {
                    val header = first == i && text[i] == '[' && TOML_HEADER.matches(text.subSequence(i, lineEnd(text, i)))
                    val indent = if (header) -1 else first - i
                    while (parents.isNotEmpty() && parents.last().first >= indent) parents.removeAt(parents.lastIndex)
                    parent = parents.lastOrNull()?.second ?: -1
                    parents += indent to i
                }
            }
            while (next < offsets.size && offsets[next] == i) result[offsets[next++]] = (brackets.lastOrNull() ?: -1) to parent
            if (i >= text.length) break
            when (text[i]) {
                '{', '[', '(' -> brackets += i
                '}', ']', ')' -> if (brackets.isNotEmpty()) brackets.removeAt(brackets.lastIndex)
                '\n' -> lineStart = i + 1
            }
            i++
        }
        return result
    }
}
