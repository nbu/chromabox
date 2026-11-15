package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.color.ColorVision
import com.bnsoft.chromabox.engine.LensContext
import com.bnsoft.chromabox.engine.LensScanner
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import java.util.Locale

/**
 * What the Markdown preview decorates (C-07.3): each color literal the lenses find in the document, with the CSS
 * background its swatch gets. Detection stays here, so the preview follows the format settings and every lens; the
 * preview's script only looks for these texts.
 */
object PreviewSwatches {
    /** A literal [text] and the CSS [background] of its swatch; [wide] for a gradient. */
    data class Swatch(val text: String, val background: String, val wide: Boolean = false)

    /** Needs a read action. Empty if the preview shows no swatches: turned off, gutter or off mode (C-07.6). */
    fun find(document: Document, fileType: FileType, project: Project?): List<Swatch> {
        val settings = ChromaboxSettings.getInstance()
        if (!settings.state.showInMarkdownPreview) return emptyList()
        if (settings.mode == DisplayMode.GUTTER || settings.mode == DisplayMode.OFF) return emptyList()
        if (document.textLength > ChromaboxSettings.MAX_DOCUMENT_LENGTH || settings.isExcluded(fileType)) return emptyList()
        val context = LensContext(fileType, (fileType as? LanguageFileType)?.language, document, project)
        val text = document.immutableCharSequence
        // Not without the ones the IDE shows swatches for: the IDE shows those in the editor only, not in the preview.
        val matches = LensScanner.scan(text, context, ChromaboxHost.lenses)
        val vision = settings.simulation
        return matches.mapNotNull { m ->
            val literal = text.subSequence(m.range.startOffset, m.range.endOffset).toString()
            when (val value = m.match.value) {
                is ChromaColor -> Swatch(literal, css(value, vision).let { "linear-gradient($it, $it)" })
                is Gradient -> Swatch(literal, gradient(value, vision), wide = true)
                else -> null
            }
        }.distinctBy { it.text }
    }

    /** The swatches as the JSON message the preview's script reads. */
    fun json(swatches: List<Swatch>): String = swatches.joinToString(",", "{\"swatches\":[", "]}") {
        "{\"text\":${quote(it.text)},\"background\":${quote(it.background)},\"wide\":${it.wide}}"
    }

    // Stops as they're drawn in the editor: left to right, at their positions, simulated (C-05).
    private fun gradient(gradient: Gradient, vision: ColorVision) = gradient.stops.joinToString(", ", "linear-gradient(to right, ", ")") {
        css(it.color, vision) + String.format(Locale.ROOT, " %.2f%%", it.position.coerceIn(0.0, 1.0) * 100)
    }

    private fun css(color: ChromaColor, vision: ColorVision): String {
        val awt = vision.simulate(color).toAwt()
        return String.format(Locale.ROOT, "rgba(%d, %d, %d, %.3f)", awt.red, awt.green, awt.blue, awt.alpha / 255.0)
    }

    private fun quote(text: String) = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' || c == ' ' || c == ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
