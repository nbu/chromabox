package com.bnsoft.chromabox.palette

import com.bnsoft.chromabox.ColorFormat
import java.util.Locale

/** The palette as a file (C-03.7), in the order it's listed. */
enum class PaletteExport(val extension: String) {
    /** Each color's hex, 8-bit RGB, alpha, uses and the formats it's written in. */
    JSON("json") {
        override fun export(entries: List<PaletteEntry>, name: String): String = buildString {
            append("{\n  \"name\": ").append(quote(name)).append(",\n  \"colors\": [")
            entries.forEachIndexed { i, entry ->
                val awt = entry.color.toAwt()
                append(if (i == 0) "\n" else ",\n")
                append("    { \"hex\": \"").append(entry.hex).append('"')
                append(", \"rgb\": [").append(awt.red).append(", ").append(awt.green).append(", ").append(awt.blue).append(']')
                append(", \"alpha\": ").append(alpha(entry))
                append(", \"uses\": ").append(entry.occurrences.size)
                append(", \"formats\": [").append(entry.formats.joinToString(", ") { quote(id(it)) }).append("] }")
            }
            append(if (entries.isEmpty()) "]\n}\n" else "\n  ]\n}\n")
        }
    },

    /** Custom properties on `:root`, named after the color. */
    CSS("css") {
        override fun export(entries: List<PaletteEntry>, name: String): String = buildString {
            append("/* ").append(name.replace("*/", "* /")).append(" */\n:root {\n")
            for (entry in entries) append("  --color-").append(entry.hex.removePrefix("#")).append(": ").append(entry.hex).append(";\n")
            append("}\n")
        }
    },

    /** A GIMP palette, which Inkscape, Krita and others read too. It has no alpha; the name keeps the full hex. */
    GPL("gpl") {
        override fun export(entries: List<PaletteEntry>, name: String): String = buildString {
            append("GIMP Palette\nName: ").append(name.replace('\n', ' ')).append("\nColumns: 0\n#\n")
            for (entry in entries) {
                val awt = entry.color.toAwt()
                append(String.format(Locale.ROOT, "%3d %3d %3d\t%s\n", awt.red, awt.green, awt.blue, entry.hex))
            }
        }
    },

    /** A table; the Markdown preview shows a swatch before each hex. */
    MARKDOWN("md") {
        override fun export(entries: List<PaletteEntry>, name: String): String = buildString {
            append("# ").append(name).append("\n\n| Color | Uses | Formats |\n| --- | ---: | --- |\n")
            for (entry in entries) {
                append("| `").append(entry.hex).append("` | ").append(entry.occurrences.size).append(" | ")
                append(entry.formats.joinToString(", ") { id(it) }).append(" |\n")
            }
        }
    };

    /** [entries] as this format's text, titled [name]. */
    abstract fun export(entries: List<PaletteEntry>, name: String): String

    companion object {
        /** A stable name for [format], for files other tools read: `css-functions`. */
        fun id(format: ColorFormat) = format.name.lowercase(Locale.ROOT).replace('_', '-')

        private fun alpha(entry: PaletteEntry): String {
            val alpha = entry.color.toAwt().alpha
            return if (alpha == 255) "1" else String.format(Locale.ROOT, "%.3f", alpha / 255.0).trimEnd('0')
        }

        private fun quote(text: String) = buildString {
            append('"')
            for (c in text) when {
                c == '"' || c == '\\' -> append('\\').append(c)
                c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> append(c)
            }
            append('"')
        }
    }
}
